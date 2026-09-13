package eu.kanade.tachiyomi.ui.reader.setting

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderSettingsScreenModel(
    readerState: StateFlow<ReaderViewModel.State>,
    // KMK -->
    // The reader's own scope. This model is built outside a navigator, where Voyager's scopes belong
    // to whichever screen registered last and outlive the reader, keeping every closed one alive.
    scope: CoroutineScope,
    // KMK <--
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    val preferences: ReaderPreferences = Injekt.get(),
) : ScreenModel {

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Lazily, null)
}
