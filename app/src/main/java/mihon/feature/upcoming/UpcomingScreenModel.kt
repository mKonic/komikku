package mihon.feature.upcoming

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapIndexedNotNull
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.insertSeparatorsReversed
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.upcoming.interactor.GetUpcomingManga
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.upcoming.service.UpcomingPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.YearMonth

class UpcomingScreenModel(
    private val getUpcomingManga: GetUpcomingManga = Injekt.get(),
    val getCategories: GetCategories = Injekt.get(),
    val upcomingPreferences: UpcomingPreferences = Injekt.get(),
) : StateScreenModel<UpcomingScreenModel.State>(State()) {
    // KMK -->
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    // KMK <--

    init {
        screenModelScope.launch {
            getUpcomingItemPreferenceFlow()
                .distinctUntilChanged()
                .flatMapLatest {
                    getUpcomingManga.subscribe(
                        includedCategories = it.filterIncludedCategories,
                        excludedCategories = it.filterExcludedCategories,
                    ).distinctUntilChanged()
                }
                .collectLatest {
                    mutableState.update { state ->
                        val upcomingItems = it.toUpcomingUIModels()
                        state.copy(
                            // KMK -->
                            isLoadingUpcoming = false,
                            // KMK <--
                            items = upcomingItems,
                            events = upcomingItems.toEvents(),
                            headerIndexes = upcomingItems.getHeaderIndexes(),
                        )
                    }
                }
        }
        screenModelScope.launch {
            getUpcomingItemPreferenceFlow()
                .map { prefs ->
                    listOf(prefs.filterIncludedCategories, prefs.filterExcludedCategories)
                        .any { it.isNotEmpty() }
                }
                .distinctUntilChanged()
                .collectLatest { hasActiveFilters ->
                    mutableState.update { it.copy(hasActiveFilters = hasActiveFilters) }
                }
        }
        // KMK -->
        screenModelScope.launch {
            mutableState.update { state ->
                val updatingItems = getUpcomingManga.updatingMangas().toUpcomingUIModels()
                state.copy(
                    isLoadingUpdating = false,
                    updatingItems = updatingItems,
                    updatingEvents = updatingItems.toEvents(),
                    updatingHeaderIndexes = updatingItems.getHeaderIndexes(),
                )
            }
        }
        // KMK <--
    }

    private fun List<Manga>.toUpcomingUIModels(): ImmutableList<UpcomingUIModel> {
        var mangaCount = 0
        return fastMap { UpcomingUIModel.Item(it) }
            .insertSeparatorsReversed { before, after ->
                if (after != null) mangaCount++

                val beforeDate = before?.manga?.expectedNextUpdate?.toLocalDate()
                val afterDate = after?.manga?.expectedNextUpdate?.toLocalDate()

                if (beforeDate != afterDate && afterDate != null) {
                    UpcomingUIModel.Header(afterDate, mangaCount).also { mangaCount = 0 }
                } else {
                    null
                }
            }
            .toImmutableList()
    }

    private fun List<UpcomingUIModel>.toEvents(): ImmutableMap<LocalDate, Int> {
        return filterIsInstance<UpcomingUIModel.Header>()
            .associate { it.date to it.mangaCount }
            .toImmutableMap()
    }

    private fun List<UpcomingUIModel>.getHeaderIndexes(): ImmutableMap<LocalDate, Int> {
        return fastMapIndexedNotNull { index, upcomingUIModel ->
            if (upcomingUIModel is UpcomingUIModel.Header) {
                upcomingUIModel.date to index
            } else {
                null
            }
        }
            .toMap()
            .toImmutableMap()
    }

    fun setSelectedYearMonth(yearMonth: YearMonth) {
        mutableState.update { it.copy(selectedYearMonth = yearMonth) }
    }

    private fun getUpcomingItemPreferenceFlow(): Flow<ItemPreferences> {
        return combine(
            upcomingPreferences.filterIncludedCategories.changes(),
            upcomingPreferences.filterExcludedCategories.changes(),
        ) { included, excluded ->
            ItemPreferences(
                filterIncludedCategories = included,
                filterExcludedCategories = excluded,
            )
        }
    }

    fun showFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.FilterSheet) }
    }

    fun resetDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun cycleCategory(category: Category) {
        val included = upcomingPreferences.filterIncludedCategories
        val excluded = upcomingPreferences.filterExcludedCategories
        when (category.id) {
            in included.get() -> {
                included.getAndSet { it - category.id }
                excluded.getAndSet { it + category.id }
            }
            in excluded.get() -> excluded.getAndSet { it - category.id }
            else -> included.getAndSet { it + category.id }
        }
    }

    @Immutable
    private data class ItemPreferences(
        val filterIncludedCategories: List<Long>,
        val filterExcludedCategories: List<Long>,
    )

    sealed interface Dialog {
        data object FilterSheet : Dialog
    }

    // KMK -->
    val restriction by lazy { libraryPreferences.autoUpdateMangaRestrictions().get() }

    fun showUpdatingMangas() {
        mutableState.update { state ->
            state.copy(
                isShowingUpdatingMangas = true,
            )
        }
    }

    fun hideUpdatingMangas() {
        mutableState.update { state ->
            state.copy(
                isShowingUpdatingMangas = false,
            )
        }
    }
    // KMK <--

    data class State(
        val selectedYearMonth: YearMonth = YearMonth.now(),
        val items: ImmutableList<UpcomingUIModel> = persistentListOf(),
        val events: ImmutableMap<LocalDate, Int> = persistentMapOf(),
        val headerIndexes: ImmutableMap<LocalDate, Int> = persistentMapOf(),
        // KMK -->
        val isLoadingUpcoming: Boolean = true,
        val isShowingUpdatingMangas: Boolean = false,
        val updatingItems: ImmutableList<UpcomingUIModel> = persistentListOf(),
        val updatingEvents: ImmutableMap<LocalDate, Int> = persistentMapOf(),
        val updatingHeaderIndexes: ImmutableMap<LocalDate, Int> = persistentMapOf(),
        val isLoadingUpdating: Boolean = true,
        // KMK <--
        val hasActiveFilters: Boolean = false,
        val dialog: Dialog? = null,
    )
}
