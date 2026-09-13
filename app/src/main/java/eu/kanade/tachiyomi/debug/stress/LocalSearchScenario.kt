package eu.kanade.tachiyomi.debug.stress

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.system.measureTimeMillis

/**
 * Searches the local source the way people and the related-titles lookup do: one query at a time, then several at
 * once, since opening an entry searches every keyword of its title together. Timed over whatever library exists.
 */
object LocalSearchScenario : StressScenario {
    override val name = "local_search"

    private val queries = listOf("stress", "library", "no entry has this title")

    override suspend fun run(context: StressContext, iteration: Int) {
        val source = Injekt.get<SourceManager>().get(LocalSource.ID) as? LocalSource
            ?: throw StressSkip("the local source is unavailable")
        val filters = source.getFilterList()

        for (query in queries) {
            var found = 0
            val ms = measureTimeMillis { found = source.getSearchManga(1, query, filters).mangas.size }
            context.step("search", mapOf("query" to query, "found" to found, "ms" to ms))
            context.alive()
        }

        var found = 0
        val ms = measureTimeMillis {
            found = coroutineScope {
                queries.map { query -> async { source.getSearchManga(1, query, filters).mangas.size } }.awaitAll().sum()
            }
        }
        context.step("search_together", mapOf("queries" to queries.size, "found" to found, "ms" to ms) + StressProbes.memory())
    }
}
