package eu.kanade.presentation.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastForEachIndexed
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsScreenModel
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun UpdatesFilterDialog(
    onDismissRequest: () -> Unit,
    screenModel: UpdatesSettingsScreenModel,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.categories),
        ),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterSheet(screenModel = screenModel)
                1 -> CategoryFilterSheet(screenModel = screenModel)
            }
        }
    }
}

@Composable
private fun ColumnScope.CategoryFilterSheet(
    screenModel: UpdatesSettingsScreenModel,
) {
    Text(
        stringResource(MR.strings.pref_filter_update_categories_details),
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

    val included by screenModel.updatesPreferences.filterIncludedCategories().collectAsState()
    val excluded by screenModel.updatesPreferences.filterExcludedCategories().collectAsState()

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

@Composable
private fun ColumnScope.FilterSheet(
    screenModel: UpdatesSettingsScreenModel,
) {
    val filterDownloaded by screenModel.updatesPreferences.filterDownloaded().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = filterDownloaded,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterDownloaded) },
    )

    val filterUnread by screenModel.updatesPreferences.filterUnread().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unread),
        state = filterUnread,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterUnread) },
    )

    val filterStarted by screenModel.updatesPreferences.filterStarted().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = filterStarted,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterStarted) },
    )

    val filterBookmarked by screenModel.updatesPreferences.filterBookmarked().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = filterBookmarked,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterBookmarked) },
    )

    HorizontalDivider(modifier = Modifier.padding(MaterialTheme.padding.small))

    val filterExcludedScanlators by screenModel.updatesPreferences.filterExcludedScanlators().collectAsState()

    Row(
        modifier = Modifier
            // KMK -->
            .clickable { screenModel.toggleSwitch(UpdatesPreferences::filterExcludedScanlators) }
            // KMK <--
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(MR.strings.action_filter_excluded_scanlators),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )

        Switch(
            checked = filterExcludedScanlators,
            // KMK -->
            onCheckedChange = { screenModel.toggleSwitch(UpdatesPreferences::filterExcludedScanlators) },
            // KMK <--
        )
    }

    // KMK -->
    HorizontalDivider(modifier = Modifier.padding(MaterialTheme.padding.small))

    val panoramaCover by screenModel.updatesPreferences.usePanoramaCover().collectAsState()

    Row(
        modifier = Modifier
            .clickable { screenModel.toggleSwitch(UpdatesPreferences::usePanoramaCover) }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(KMR.strings.action_panorama_cover),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )

        Switch(
            checked = panoramaCover,
            onCheckedChange = { screenModel.toggleSwitch(UpdatesPreferences::usePanoramaCover) },
        )
    }
    // KMK <--
}
