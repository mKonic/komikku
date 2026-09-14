package eu.kanade.tachiyomi.debug.stress

import kotlinx.coroutines.delay
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.GetLibraryManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.system.measureTimeMillis

/**
 * Categories made, filled, renamed, reordered and deleted, through the same interactors the category screen uses.
 *
 * Nothing else in the run writes to the category tables while the library screen is watching them, and the library
 * tabs are built from exactly that. Deleting a category an entry is still in is the interesting half: the entry has
 * to end up somewhere rather than disappearing, and the tab it was under has to go.
 *
 * Only categories this scenario made are touched, and it tidies them up itself, so a run leaves a library with the
 * categories it started with.
 */
object CategoryScenario : StressScenario {
    override val name = "categories"

    /** Names this scenario owns. Anything else in the library is left alone, however tempting. */
    private const val PREFIX = "stress "

    private const val PER_PASS = 5
    private const val SETTLE_MILLIS = 300L

    private val getCategories by lazy { Injekt.get<GetCategories>() }
    private val createCategory by lazy { Injekt.get<CreateCategoryWithName>() }
    private val renameCategory by lazy { Injekt.get<RenameCategory>() }
    private val reorderCategory by lazy { Injekt.get<ReorderCategory>() }
    private val deleteCategory by lazy { Injekt.get<DeleteCategory>() }
    private val setMangaCategories by lazy { Injekt.get<SetMangaCategories>() }

    override suspend fun run(context: StressContext, iteration: Int) {
        // Anything left behind by a process that died mid-pass, so a long run does not accumulate them.
        val leftover = mine()
        if (leftover.isNotEmpty()) {
            leftover.forEach { deleteCategory.await(it.id) }
            context.step("swept", mapOf("categories" to leftover.size))
        }

        val made = mutableListOf<Long>()
        try {
            val createMs = measureTimeMillis {
                repeat(PER_PASS) { index -> createCategory.await("$PREFIX$iteration-$index") }
            }
            made += mine().map { it.id }
            context.step("created", mapOf("count" to made.size, "ms" to createMs))
            if (made.isEmpty()) throw StressSkip("categories could not be created")

            val entries = Injekt.get<GetLibraryManga>().await().map { it.manga.id }.distinct().take(PER_PASS)
            entries.forEachIndexed { index, mangaId ->
                // The entry keeps whatever it was in and gains one of ours, which is what the category sheet does.
                val existing = getCategories.await(mangaId).map { it.id }
                setMangaCategories.await(mangaId, (existing + made[index % made.size]).distinct())
            }
            context.step("filled", mapOf("entries" to entries.size))

            mine().forEach { category -> renameCategory.await(category.id, "${category.name} renamed") }
            context.step("renamed", mapOf("count" to made.size))
            delay(SETTLE_MILLIS)

            // To the front, which moves every other category's order and is where an off by one shows up.
            mine().forEach { category -> reorderCategory.await(category, 0) }
            context.step("reordered", mapOf("count" to made.size))

            val settled = awaitUiSettled()
            context.step("settled", mapOf("settled" to settled))
        } finally {
            val toRemove = mine()
            toRemove.forEach { runCatching { deleteCategory.await(it.id) } }
            context.journal.record(
                "step",
                mapOf("scenario" to name, "event" to "deleted", "count" to toRemove.size),
            )
        }
    }

    private suspend fun mine() = getCategories.await().filter { it.name.startsWith(PREFIX) }
}
