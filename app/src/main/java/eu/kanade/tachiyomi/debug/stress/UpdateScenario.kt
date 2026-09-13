package eu.kanade.tachiyomi.debug.stress

import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.system.measureTimeMillis

/**
 * A library update over every entry it may reach: the local ones, or the whole library when the run allows the
 * network. It goes through the same job the app schedules, and waits for any update already going first.
 */
object UpdateScenario : StressScenario {
    override val name = "update"

    /** Generous per entry, so the limit scales with the library rather than being one number for all of it. */
    private const val LOCAL_MILLIS_PER_ENTRY = 2_000L
    private const val ONLINE_MILLIS_PER_ENTRY = 30_000L

    override suspend fun run(context: StressContext, iteration: Int) {
        val app = context.app
        val library = Injekt.get<GetLibraryManga>().await().map { it.manga }.distinctBy { it.id }
        val entries = if (context.network) library else library.filter { it.source == LocalSource.ID }
        if (entries.isEmpty()) throw StressSkip("no library entries it may update")

        LibraryUpdateJob.isActiveFlow(app).first { !it }
        check(LibraryUpdateJob.startNow(app, mangaIds = entries.map { it.id })) { "the library update did not start" }
        context.step("started", mapOf("entries" to entries.size))

        val limit = entries.size * if (context.network) ONLINE_MILLIS_PER_ENTRY else LOCAL_MILLIS_PER_ENTRY
        var finished = false
        val ms = measureTimeMillis {
            finished = withTimeoutOrNull(limit) {
                LibraryUpdateJob.isActiveFlow(app).first { active ->
                    context.alive()
                    !active
                }
            } != null
        }
        if (!finished) {
            LibraryUpdateJob.stop(app)
            error("the library update of ${entries.size} entries was still going after ${limit / 1000} s")
        }
        context.step("updated", mapOf("entries" to entries.size, "ms" to ms))
    }
}
