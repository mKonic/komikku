package eu.kanade.tachiyomi.debug.stress

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * A search on every installed online source, as many at a time as global search runs. Every source failing is a
 * failure; some failing is only recorded, since sources go down on their own.
 */
object SearchScenario : StressScenario {
    override val name = "search"
    override val needsNetwork = true

    private const val PARALLEL_SOURCES = 5
    private val queries = listOf("one", "love", "dragon", "school", "world")

    override suspend fun run(context: StressContext, iteration: Int) {
        val sources = Injekt.get<SourceManager>().getOnlineSources()
        if (sources.isEmpty()) throw StressSkip("no online sources are installed")

        val query = queries[iteration % queries.size]
        val succeeded = AtomicInteger()
        val failed = AtomicInteger()
        val permits = Semaphore(PARALLEL_SOURCES)
        val ms = measureTimeMillis {
            coroutineScope {
                sources.map { source ->
                    async {
                        permits.withPermit {
                            runCatching { source.getSearchManga(1, query, source.getFilterList()) }
                                .onSuccess { succeeded.incrementAndGet() }
                                .onFailure { failed.incrementAndGet() }
                            context.alive()
                        }
                    }
                }.awaitAll()
            }
        }
        context.step(
            "searched",
            mapOf(
                "query" to query,
                "sources" to sources.size,
                "succeeded" to succeeded.get(),
                "failed" to failed.get(),
                "ms" to ms,
            ),
        )
        check(succeeded.get() > 0) { "every source failed to search for \"$query\"" }
    }
}
