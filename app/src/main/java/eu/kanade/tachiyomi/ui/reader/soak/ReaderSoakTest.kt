package eu.kanade.tachiyomi.ui.reader.soak

import android.content.Context
import android.content.Intent
import android.os.Debug
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.toast
import exh.util.defaultReaderType
import exh.util.mangaType
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Debug stress test for the reader. Opens a list of chapters one after another, each in a fresh
 * reader, turns every page the moment the one before it is on screen, and appends the app's memory to
 * a CSV around every chapter. Every step gets a row too, so a run that stops shows where. Pages are
 * moved through the viewer itself, so every reading mode works the same way.
 *
 * The reader is incognito while a session runs, so nothing reaches history, progress or trackers.
 * Leaving a reader while it reads pages stops the session; one that closes or never loads before it
 * gets that far is skipped.
 *
 * Besides the debug screen, a run starts straight from adb:
 * `am start -n <package>/eu.kanade.tachiyomi.ui.main.MainActivity -a komikku.soak.START --ei chapters 2 --ei rounds 3`
 * and `-a komikku.soak.SEED_LOCAL` first fills the library from the local source on a device with no
 * extensions.
 */
object ReaderSoakTest {

    const val ACTION_START = "komikku.soak.START"
    const val ACTION_SEED_LOCAL = "komikku.soak.SEED_LOCAL"
    const val EXTRA_CHAPTERS = "chapters"
    const val EXTRA_ROUNDS = "rounds"
    const val EXTRA_SOAK = "komikku.soak"

    data class Target(val mangaId: Long, val chapterId: Long)

    private class Session(val targets: List<Target>, val output: File) {
        var next = 0
        var reading = false
        var advancing = false
    }

    private data class Candidate(val mangaId: Long, val lastRead: Long, val readingMode: Int)

    private data class PageCounts(val pages: Int = 0, val displayed: Int = 0, val failed: Int = 0, val timedOut: Int = 0)

    @Volatile
    private var session: Session? = null

    /** Samples and writes rows one at a time, in order, off the main thread. */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReaderSoakTest").apply { isDaemon = true }
    }

    val isActive: Boolean get() = session != null

    /**
     * One series for every reading mode the library uses, from installed sources, taking the most
     * recently read of each. A run over them goes through every viewer that gets used.
     */
    suspend fun pickSeries(): List<Long> {
        val readerPreferences = Injekt.get<ReaderPreferences>()
        val defaultMode = readerPreferences.defaultReadingMode().get()
        val autoWebtoon = readerPreferences.useAutoWebtoon().get()
        val sourceManager = Injekt.get<SourceManager>()
        return Injekt.get<GetLibraryManga>().await()
            .distinctBy { it.id }
            .mapNotNull { item ->
                val source = sourceManager.get(item.manga.source) ?: return@mapNotNull null
                if (item.totalChapters <= 0) return@mapNotNull null
                // Resolved as ReaderViewModel.getMangaReadingMode does, so each pick opens a different viewer.
                val stored = ReadingMode.fromPreference(item.manga.readingMode.toInt())
                val mode = when {
                    stored != ReadingMode.DEFAULT -> stored.flagValue
                    autoWebtoon ->
                        item.manga.defaultReaderType(item.manga.mangaType(sourceName = source.name)) ?: defaultMode
                    else -> defaultMode
                }
                Candidate(item.id, item.lastRead, mode)
            }
            .sortedByDescending { it.lastRead }
            .distinctBy { it.readingMode }
            .map { it.mangaId }
    }

    /**
     * Adds every title in the local source to the library with its chapters, so a device without
     * extensions has something to read - for the local AVD checks.
     */
    suspend fun seedLocalSource(context: Context) {
        val source = Injekt.get<SourceManager>().get(LocalSource.ID)
        if (source == null) {
            withUIContext { context.toast("Soak test: the local source is unavailable") }
            return
        }
        val titles = source.getPopularManga(1).mangas.map { it.toDomainManga(LocalSource.ID) }
        val syncChapters = Injekt.get<SyncChaptersWithSource>()
        val updateManga = Injekt.get<UpdateManga>()
        val added = Injekt.get<NetworkToLocalManga>()(titles).count { manga ->
            val update = source.getMangaUpdate(
                manga.toSManga(),
                emptyList(),
                fetchDetails = false,
                fetchChapters = true,
            )
            syncChapters.await(update.chapters, manga, source)
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
        withUIContext { context.toast("Soak test: added $added local series") }
    }

    /** Reads the first [chaptersPerSeries] chapters of each series in turn, [rounds] times over. */
    suspend fun start(context: Context, mangaIds: List<Long>, chaptersPerSeries: Int, rounds: Int) {
        val library = Injekt.get<GetLibraryManga>().await().associate { it.id to it.manga }
        val getChapters = Injekt.get<GetChaptersByMangaId>()
        val round = mangaIds.flatMap { id ->
            val manga = library[id] ?: return@flatMap emptyList()
            getChapters.await(id, applyFilter = true)
                .sortedWith(getChapterSort(manga, sortDescending = false))
                .take(chaptersPerSeries)
                .map { Target(id, it.id) }
        }
        withUIContext { begin(context, List(rounds) { round }.flatten()) }
    }

    private fun begin(context: Context, targets: List<Target>) {
        if (targets.isEmpty()) {
            context.toast("Soak test: no chapters to read")
            return
        }
        if (session != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val output = File(dir, "soak-$stamp.csv")
        val s = Session(targets, output)
        session = s
        writer.execute { runCatching { output.writeText(HEADER) } }
        record(s, "start", null, sampleMemory = true)
        openNext(context.applicationContext)
    }

    /** Runs inside a reader the session opened, and closes it once its pages are done. */
    suspend fun drive(activity: ReaderActivity) {
        val s = session ?: return
        val target = s.targets.getOrNull(s.next - 1)
        record(s, "reader", target)
        val ready = withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) {
            val chapter = activity.viewModel.state.mapNotNull { it.viewerChapters?.currChapter }.first()
            record(s, "loaded", target, PageCounts(chapter.pages.orEmpty().size))
            chapter to activity.viewModel.state.mapNotNull { it.viewer }.first()
        }
        if (ready == null) {
            record(s, "load_timed_out", target)
            activity.finish()
            return
        }
        val (chapter, viewer) = ready
        val pages = chapter.pages.orEmpty()
        val mode = ReadingMode.fromPreference(activity.viewModel.getMangaReadingMode()).name
        s.reading = true
        record(s, "opened", target, PageCounts(pages.size), mode, sampleMemory = true)

        var counts = PageCounts(pages.size)
        for (page in pages) {
            viewer.moveToPage(page)
            // Never onto a blank: wait until the viewer has the page on screen or gave up on it.
            val outcome = withTimeoutOrNull(PAGE_TIMEOUT_MILLIS) {
                combine(page.statusFlow, page.displayState) { status, display ->
                    when {
                        display == ReaderPage.DisplayState.Displayed -> display
                        display == ReaderPage.DisplayState.Failed || status is Page.State.Error ->
                            ReaderPage.DisplayState.Failed
                        else -> null
                    }
                }
                    .filterNotNull()
                    .first()
            }
            counts = when (outcome) {
                ReaderPage.DisplayState.Displayed -> {
                    // The page is in the view; let a frame with it reach the screen before moving on.
                    awaitFrame()
                    awaitFrame()
                    counts.copy(displayed = counts.displayed + 1)
                }
                null -> counts.copy(timedOut = counts.timedOut + 1)
                else -> counts.copy(failed = counts.failed + 1)
            }
        }

        record(s, "read", target, counts, mode)
        s.advancing = true
        activity.finish()
    }

    /** From the reader's onDestroy. The reader is singleTask, so the next one can only start now. */
    fun onReaderDestroyed(activity: ReaderActivity) {
        val s = session ?: return
        if (!activity.isFinishing) return
        when {
            s.advancing -> openNext(activity.applicationContext)
            !s.reading -> {
                record(s, "skipped", s.targets.getOrNull(s.next - 1))
                openNext(activity.applicationContext)
            }
            else -> stop(activity.applicationContext, "stopped")
        }
    }

    private fun openNext(context: Context) {
        val s = session ?: return
        s.reading = false
        s.advancing = false
        val target = s.targets.getOrNull(s.next)
        if (target == null) {
            stop(context, "done")
            return
        }
        s.next++
        val intent = ReaderActivity.newIntent(context, target.mangaId, target.chapterId, 0)
            .putExtra(EXTRA_SOAK, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { record(s, "launch", target) }
            .onFailure {
                logcat(LogPriority.ERROR, it) { "Soak test could not open a reader" }
                stop(context, "launch_failed")
            }
    }

    private fun stop(context: Context, event: String) {
        val s = session ?: return
        record(s, event, null, sampleMemory = true)
        // Every reader is gone by now, so any reader still in this dump is a leak - once decodes the
        // last one started have finished. Image preparation runs on CPU that cancelling does not
        // interrupt, and holds its viewer until it returns.
        val heapDump = s.output.path.removeSuffix(".csv") + ".hprof"
        writer.execute {
            Thread.sleep(HEAP_DUMP_SETTLE_MILLIS)
            Runtime.getRuntime().gc()
            runCatching { Debug.dumpHprofData(heapDump) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Soak test could not dump the heap" } }
        }
        session = null
        context.toast("Soak test $event: ${s.output.absolutePath}")
    }

    private fun record(
        s: Session,
        event: String,
        target: Target?,
        counts: PageCounts = PageCounts(),
        mode: String = "",
        sampleMemory: Boolean = false,
    ) {
        val time = LocalDateTime.now()
        val chapter = s.next
        writer.execute {
            val memory = if (sampleMemory) sampleMemory() else List(MEMORY_COLUMNS) { "" }
            val line = listOf(
                time,
                chapter,
                event,
                target?.mangaId ?: "",
                target?.chapterId ?: "",
                mode,
                counts.pages,
                counts.displayed,
                counts.failed,
                counts.timedOut,
            ) + memory
            runCatching { s.output.appendText(line.joinToString(",") + "\n") }
                .onFailure { logcat(LogPriority.ERROR, it) { "Soak test could not write ${s.output}" } }
        }
    }

    /**
     * Collects first, so the numbers are what is still held rather than garbage the collector has not
     * reached yet. Finalizers get a bounded wait, so a stuck one cannot hold up the run.
     */
    private fun sampleMemory(): List<Any> {
        Runtime.getRuntime().gc()
        Thread { System.runFinalization() }.apply {
            isDaemon = true
            start()
            join(FINALIZE_WAIT_MILLIS)
        }
        Runtime.getRuntime().gc()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return listOf(
            info.totalPss,
            info.getMemoryStat("summary.native-heap"),
            info.getMemoryStat("summary.graphics"),
            info.getMemoryStat("summary.java-heap"),
        )
    }

    private const val HEADER = "time,chapter,event,manga_id,chapter_id,mode,pages,displayed,failed,timed_out," +
        "total_pss_kb,native_heap_kb,graphics_kb,java_heap_kb\n"

    private const val MEMORY_COLUMNS = 4

    private const val FINALIZE_WAIT_MILLIS = 5_000L

    private const val HEAP_DUMP_SETTLE_MILLIS = 15_000L

    // Millisecond constants, not Duration properties: lint's ExperimentalDetector crashes resolving a
    // value-class property on this object ("No fir element was found for KtProperty").
    private const val PAGE_TIMEOUT_MILLIS = 30_000L

    private const val LOAD_TIMEOUT_MILLIS = 60_000L
}
