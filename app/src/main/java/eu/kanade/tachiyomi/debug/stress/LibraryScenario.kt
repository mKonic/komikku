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
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * A library that grows every pass, sorted and searched from the library screen. It grows by [SERIES_PER_PASS] local
 * series a pass until the share of free storage it may use is full, so how far it gets depends on the device, not
 * on a number picked here. The other scenarios read, back up and update whatever it has built.
 */
object LibraryScenario : StressScenario {
    override val name = "library"

    private const val PREFIX = "Stress Library "
    private const val SERIES_PER_PASS = 250
    private const val STORAGE_SHARE = 0.05
    private const val BYTES_PER_SERIES_ESTIMATE = 16 * 1024L

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

        val budget = seriesBudget(localDir)
        val target = min(SERIES_PER_PASS.toLong() * (iteration + 1), budget).toInt()
        val existing = withIOContext { localDir.listFiles().orEmpty().count { it.name.orEmpty().startsWith(PREFIX) } }
        if (existing < target) {
            generate(context, localDir, existing, target)
            addToLibrary(context)
        }
        context.step("size", mapOf("series" to maxOf(existing, target), "budget" to budget))

        exercise(context)
    }

    /** How many generated series fit in the share of free storage the scenario may take. */
    private fun seriesBudget(dir: UniFile): Long {
        val free = DiskUtil.getAvailableStorageSpace(dir)
        if (free <= 0) return SERIES_PER_PASS.toLong()
        return (free * STORAGE_SHARE / BYTES_PER_SERIES_ESTIMATE).toLong().coerceAtLeast(SERIES_PER_PASS.toLong())
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
