package mihon.feature.upcoming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastForEachIndexed
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState

class UpcomingScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel { UpcomingScreenModel() }
        val state by screenModel.state.collectAsState()

        when (state.dialog) {
            is UpcomingScreenModel.Dialog.FilterSheet -> UpcomingFilterDialog(screenModel = screenModel)
            null -> {}
        }

        UpcomingScreenContent(
            state = state,
            setSelectedYearMonth = screenModel::setSelectedYearMonth,
            onClickUpcoming = { navigator.push(MangaScreen(it.id)) },
            onClickFilter = screenModel::showFilterDialog,
            hasActiveFilters = state.hasActiveFilters,
            // KMK -->
            showUpdatingMangas = screenModel::showUpdatingMangas,
            hideUpdatingMangas = screenModel::hideUpdatingMangas,
            isPredictReleaseDate = MANGA_OUTSIDE_RELEASE_PERIOD in screenModel.restriction,
            // KMK <--
        )
    }
}

@Composable
private fun UpcomingFilterDialog(
    screenModel: UpcomingScreenModel,
) {
    TabbedDialog(
        onDismissRequest = screenModel::resetDialog,
        tabTitles = persistentListOf(
            stringResource(MR.strings.categories),
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            CategoryFilterSheet(screenModel = screenModel)
        }
    }
}

@Composable
private fun ColumnScope.CategoryFilterSheet(
    screenModel: UpcomingScreenModel,
) {
    Text(
        stringResource(MR.strings.pref_filter_upcoming_categories_details),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    )

    HorizontalDivider(modifier = Modifier.padding(MaterialTheme.padding.extraSmall))

    val allCategories by screenModel.getCategories.subscribe().collectAsState(initial = emptyList())

    if (allCategories.isEmpty()) {
        // The list always contains the system category, so an empty one only means it is still loading
        LoadingScreen(modifier = Modifier.padding(MaterialTheme.padding.medium))
        return
    }

    val included by screenModel.upcomingPreferences.filterIncludedCategories.collectAsState()
    val excluded by screenModel.upcomingPreferences.filterExcludedCategories.collectAsState()

    val selected = remember {
        allCategories.map { category ->
            when (category.id) {
                in included -> TriState.ENABLED_IS
                in excluded -> TriState.ENABLED_NOT
                else -> TriState.DISABLED
            }
        }.toMutableStateList()
    }

    Column {
        allCategories.fastForEachIndexed { idx, category ->
            val state = selected[idx]
            TriStateItem(
                label = category.visualName,
                state = state,
                onClick = {
                    selected[idx] = state.next()
                    screenModel.cycleCategory(category)
                },
            )
        }
    }
}
