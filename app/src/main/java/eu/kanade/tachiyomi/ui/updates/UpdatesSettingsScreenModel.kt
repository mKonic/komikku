package eu.kanade.tachiyomi.ui.updates

import cafe.adriel.voyager.core.model.ScreenModel
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class UpdatesSettingsScreenModel(
    val updatesPreferences: UpdatesPreferences = Injekt.get(),
    val getCategories: GetCategories = Injekt.get(),
) : ScreenModel {

    fun cycleCategory(category: Category) {
        val included = updatesPreferences.filterIncludedCategories()
        val excluded = updatesPreferences.filterExcludedCategories()
        when (category.id) {
            in included.get() -> {
                included.getAndSet { it - category.id }
                excluded.getAndSet { it + category.id }
            }
            in excluded.get() -> excluded.getAndSet { it - category.id }
            else -> included.getAndSet { it + category.id }
        }
    }

    fun toggleFilter(preference: (UpdatesPreferences) -> Preference<TriState>) {
        preference(updatesPreferences).getAndSet {
            it.next()
        }
    }

    // KMK -->
    fun toggleSwitch(preference: (UpdatesPreferences) -> Preference<Boolean>) {
        preference(updatesPreferences).getAndSet { !it }
    }
    // KMK <--
}
