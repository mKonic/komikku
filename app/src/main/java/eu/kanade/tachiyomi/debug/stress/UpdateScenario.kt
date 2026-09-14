package eu.kanade.tachiyomi.debug.stress

import androidx.work.WorkInfo
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.system.measureTimeMillis

/**
 * A library update over every entry it may reach: the local ones, or the whole library when the run allows the
 * network. It goes through the same job the app schedules, waits for any update already going first, and passes only
 * if the update it started succeeded: a job WorkManager could not build or that failed also just stops running.
 */
object UpdateScenario : StressScenario {
    override val name = "update"

    /** Generous per entry, so the limit scales with the library rather than being one number for all of it. */
    private const val LOCAL_MILLIS_PER_ENTRY = 2_000L

    /** How long to wait for an update that was already going, an earlier pass or one WorkManager resumed. */
    private const val SETTLE_MILLIS = 120_000L
    private const val ONLINE_MILLIS_PER_ENTRY = 30_000L

    override suspend fun run(context: StressContext, iteration: Int) {
        val app = context.app
        val library = Injekt.get<GetLibraryManga>().await().map { it.manga }.distinctBy { it.id }
        val entries = if (context.network) library else library.filter { it.source == LocalSource.ID }
        if (entries.isEmpty()) throw StressSkip("no library entries it may update")

        // An update this pass did not start is not its to wait out: WorkManager resumes one the app was killed
        // during, and over a large library that outlasts any watchdog.
        val settled = withTimeoutOrNull(SETTLE_MILLIS) {
            LibraryUpdateJob.isActiveFlow(app).first { active ->
                context.alive()
                !active
            }
        } != null
        if (!settled) throw StressSkip("an earlier library update is still going")
        val earlier = withIOContext { LibraryUpdateJob.manualUpdates(app).map { it.id }.toSet() }
        check(LibraryUpdateJob.startNow(app, mangaIds = entries.map { it.id })) { "the library update did not start" }
        context.step("started", mapOf("entries" to entries.size))

        val limit = entries.size * if (context.network) ONLINE_MILLIS_PER_ENTRY else LOCAL_MILLIS_PER_ENTRY
        var finished = false
        val ms = measureTimeMillis {
            // The active flow only emits when the job starts and ends, so an update of a large library looks idle to
            // the watchdog. Every entry it finishes is progress.
            finished = coroutineScope {
                val progress = launch { LibraryUpdateJob.completedCount.collect { context.alive() } }
                val ended = withTimeoutOrNull(limit) {
                    LibraryUpdateJob.isActiveFlow(app).first { active ->
                        context.alive()
                        !active
                    }
                } != null
                progress.cancel()
                ended
            }
        }
        if (!finished) {
            LibraryUpdateJob.stop(app)
            error("the library update of ${entries.size} entries was still going after ${limit / 1000} s")
        }

        val started = withIOContext { LibraryUpdateJob.manualUpdates(app).filterNot { it.id in earlier } }
        check(started.isNotEmpty()) { "the library update it started left no record in WorkManager" }
        started.firstOrNull { it.state != WorkInfo.State.SUCCEEDED }?.let {
            error("the library update of ${entries.size} entries ended ${it.state} after $ms ms")
        }
        context.step("updated", mapOf("entries" to entries.size, "ms" to ms))
    }
}
