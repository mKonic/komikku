package eu.kanade.tachiyomi.debug.stress

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * A library sorted and searched from the library screen, then resized for the next pass. It grows by
 * [SERIES_PER_PASS] local series while they fit in the share of free storage it may use and the collected Java heap
 * stays under [HEAP_SHARE] of its limit, and gives a pass's worth back when the heap is over it. How big it gets
 * depends on the device, and an eternal run settles just under what the process can hold instead of running out of
 * memory every few minutes, which hides slower problems. The other scenarios read, back up and update what it built.
 */
object LibraryScenario : StressScenario {
    override val name = "library"

    private const val PREFIX = "Stress Library "
    private const val SERIES_PER_PASS = 250
    private const val STORAGE_SHARE = 0.05
    private const val BYTES_PER_SERIES_ESTIMATE = 16 * 1024L

    /** Backups and restores copy the whole library, so the steady state leaves room for a second copy. */
    private const val HEAP_SHARE = 0.5

    private val sortTypes = listOf(
        LibrarySort.Type.Alphabetical,
        LibrarySort.Type.LastRead,
        LibrarySort.Type.LastUpdate,
        LibrarySort.Type.UnreadCount,
        LibrarySort.Type.TotalChapters,
        LibrarySort.Type.LatestChapter,
        LibrarySort.Type.ChapterFetchDate,
        LibrarySort.Type.DateAdded,
        LibrarySort.Type.Random,
    )
    private val directions = listOf(LibrarySort.Direction.Ascending, LibrarySort.Direction.Descending)
    private val queries = listOf("Stress", "Library 00", "no entry has this title", "")

    override suspend fun run(context: StressContext, iteration: Int) {
        val localDir = Injekt.get<StorageManager>().getLocalSourceDirectory()
            ?: throw StressSkip("no storage folder is set")

        exercise(context)

        val budget = seriesBudget(localDir)
        val existing = withIOContext { localDir.listFiles().orEmpty().count { it.name.orEmpty().startsWith(PREFIX) } }
        val heap = heapShare()
        val series = when {
            heap > HEAP_SHARE && existing > SERIES_PER_PASS -> existing - shrink(context, localDir, SERIES_PER_PASS)
            heap <= HEAP_SHARE && existing < budget -> {
                val target = minOf(existing + SERIES_PER_PASS.toLong(), budget).toInt()
                generate(context, localDir, existing, target)
                addToLibrary(context)
                target
            }
            else -> existing
        }
        context.step("size", mapOf("series" to series, "budget" to budget, "heapPercent" to (heap * 100).roundToInt()))
    }

    /** How many generated series fit in the share of free storage the scenario may take. */
    private fun seriesBudget(dir: UniFile): Long {
        val free = DiskUtil.getAvailableStorageSpace(dir)
        if (free <= 0) return SERIES_PER_PASS.toLong()
        return (free * STORAGE_SHARE / BYTES_PER_SERIES_ESTIMATE).toLong().coerceAtLeast(SERIES_PER_PASS.toLong())
    }

    /** Java heap in use once collected, as a share of the most this process may have. */
    private fun heapShare(): Double {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        return (runtime.totalMemory() - runtime.freeMemory()).toDouble() / runtime.maxMemory()
    }

    private suspend fun generate(context: StressContext, localDir: UniFile, from: Int, until: Int) = withIOContext {
        val page = pageBytes()
        for (index in from until until) {
            val series = localDir.createDirectory(PREFIX + "%06d".format(index)) ?: error("could not create a series")
            val chapter = series.createDirectory("Chapter 1") ?: error("could not create a chapter")
            val file = chapter.createFile("001.jpg") ?: error("could not create a page")
            file.openOutputStream().use { it.write(page) }
            if (index % 50 == 0) context.alive()
        }
        context.step("generated", mapOf("from" to from, "until" to until))
    }

    private fun pageBytes(): ByteArray {
        val bitmap = createBitmap(64, 96).apply { eraseColor(Color.rgb(40, 90, 160)) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private suspend fun addToLibrary(context: StressContext) {
        val source = Injekt.get<SourceManager>().get(LocalSource.ID) as? LocalSource
            ?: throw StressSkip("the local source is unavailable")
        val syncChapters = Injekt.get<SyncChaptersWithSource>()
        val updateManga = Injekt.get<UpdateManga>()
        val titles = source.getPopularManga(1).mangas
            .filter { it.title.startsWith(PREFIX) }
            .map { it.toDomainManga(LocalSource.ID) }
        val added = Injekt.get<NetworkToLocalManga>()(titles).filterNot { it.favorite }
        added.forEachIndexed { index, manga ->
            val update = source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true)
            syncChapters.await(update.chapters, manga, source)
            updateManga.awaitUpdateFavorite(manga.id, true)
            if (index % 25 == 0) context.alive()
        }
        context.step("added", mapOf("series" to added.size))
    }

    /**
     * Takes the newest [count] generated series out of the library and deletes their folders, so the next generated
     * series carry on from the highest one left. Returns how many went.
     */
    private suspend fun shrink(context: StressContext, localDir: UniFile, count: Int): Int = withIOContext {
        val updateManga = Injekt.get<UpdateManga>()
        val newest = Injekt.get<GetLibraryManga>().await()
            .map { it.manga }
            .filter { it.source == LocalSource.ID && it.url.startsWith(PREFIX) }
            .distinctBy { it.id }
            .sortedByDescending { it.url }
            .take(count)
        newest.forEachIndexed { index, manga ->
            updateManga.awaitUpdateFavorite(manga.id, false)
            localDir.findFile(manga.url)?.delete()
            if (index % 25 == 0) context.alive()
        }
        context.step("removed", mapOf("series" to newest.size))
        newest.size
    }

    /** Every sort, both ways, then searches, each timed until the library screen has settled. */
    private suspend fun exercise(context: StressContext) {
        val navigator = context.navigator()
        withContext(Dispatchers.Main) { navigator.popUntilRoot() }
        HomeScreen.openTab(HomeScreen.Tab.Library())
        awaitUiSettled()

        val sorting = Injekt.get<LibraryPreferences>().sortingMode()
        val original = sorting.get()
        try {
            for (type in sortTypes) {
                for (direction in directions) {
                    var settled = true
                    val ms = measureTimeMillis {
                        sorting.set(LibrarySort(type, direction))
                        settled = awaitUiSettled()
                    }
                    context.step(
                        "sort",
                        mapOf("type" to type.javaClass.simpleName, "direction" to direction.javaClass.simpleName, "ms" to ms, "settled" to settled),
                    )
                }
            }
            for (query in queries) {
                var settled = true
                val ms = measureTimeMillis {
                    HomeScreen.search(query)
                    settled = awaitUiSettled()
                }
                context.step("search", mapOf("query" to query, "ms" to ms, "settled" to settled))
            }
        } finally {
            sorting.set(original)
        }
    }
}
