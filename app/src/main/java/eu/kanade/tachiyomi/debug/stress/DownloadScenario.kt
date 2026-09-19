package eu.kanade.tachiyomi.debug.stress

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.system.measureTimeMillis

/**
 * Downloads chapters of a random library entry from an online source, one more each pass, through the app's own
 * download queue, then deletes them again so a run does not fill the storage it is measuring.
 */
object DownloadScenario : StressScenario {
    override val name = "downloads"
    override val needsNetwork = true

    /** Generous per chapter, so the limit scales with the work rather than being one number for any size. */
    private const val MILLIS_PER_CHAPTER = 120_000L
    private const val QUEUE_POLL_MILLIS = 2_000L

    override suspend fun run(context: StressContext, iteration: Int) {
        val sourceManager = Injekt.get<SourceManager>()
        val downloadManager = Injekt.get<DownloadManager>()
        val getChapters = Injekt.get<GetChaptersByMangaId>()
        val count = 1 + iteration

        val library = Injekt.get<GetLibraryManga>().await().map { it.manga }.distinctBy { it.id }.shuffled()
        var picked: Triple<tachiyomi.domain.manga.model.Manga, HttpSource, List<tachiyomi.domain.chapter.model.Chapter>>? =
            null
        for (manga in library) {
            val source = sourceManager.get(manga.source) as? HttpSource ?: continue
            val chapters = getChapters.await(manga.id)
                .filterNot {
                    downloadManager.isChapterDownloaded(it.name, it.scanlator, it.url, manga.ogTitle, manga.source)
                }
                .take(count)
            if (chapters.isNotEmpty()) {
                picked = Triple(manga, source, chapters)
                break
            }
        }
        val (manga, source, chapters) = picked
            ?: throw StressSkip("no library entry from an online source has chapters left to download")
        val ids = chapters.map { it.id }.toSet()

        downloadManager.downloadChapters(manga, chapters)
        context.step("queued", mapOf("manga" to manga.id, "source" to source.name, "chapters" to chapters.size))

        val limit = chapters.size * MILLIS_PER_CHAPTER
        var remaining: List<Download> = emptyList()
        try {
            val ms = measureTimeMillis {
                val deadline = System.currentTimeMillis() + limit
                while (true) {
                    remaining = downloadManager.queueState.value.filter { it.chapter.id in ids }
                    if (remaining.all { it.status == Download.State.ERROR }) break
                    check(System.currentTimeMillis() < deadline) {
                        "downloading ${chapters.size} chapters was still going after ${limit / 1000} s"
                    }
                    context.alive()
                    delay(QUEUE_POLL_MILLIS)
                }
            }
            if (remaining.isNotEmpty()) downloadManager.cancelQueuedDownloads(remaining)

            val downloaded = chapters.count {
                downloadManager.isChapterDownloaded(it.name, it.scanlator, it.url, manga.ogTitle, manga.source, skipCache = true)
            }
            context.step("downloaded", mapOf("downloaded" to downloaded, "failed" to remaining.size, "ms" to ms))
            check(downloaded > 0) { "none of the ${chapters.size} chapters downloaded" }
        } finally {
            // The wait above ends by timing out as readily as by finishing, and the scenario is meant to leave the
            // storage it measures as it found it. Whatever is still queued is cancelled before the delete, or a
            // download in flight writes its chapter back out after the files have gone.
            val queued = downloadManager.queueState.value.filter { it.chapter.id in ids }
            if (queued.isNotEmpty()) downloadManager.cancelQueuedDownloads(queued)
            downloadManager.deleteChapters(chapters, manga, source, ignoreCategoryExclusion = true)
        }
    }
}
