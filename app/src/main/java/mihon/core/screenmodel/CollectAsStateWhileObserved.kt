package mihon.core.screenmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState

/**
 * Reads the model's state and, for as long as this composable is in composition, tells the model it
 * is being observed so its gated flows run.
 *
 * Use this instead of `screenModel.state.collectAsState()` on any [ObservedStateScreenModel]:
 * without it the model is never marked observed and its gated pipelines never start, which shows up
 * immediately as a screen stuck on its initial state.
 */
@Composable
fun <S> ObservedStateScreenModel<S>.collectAsStateWhileObserved(): State<S> {
    DisposableEffect(this) {
        onObserverAdded()
        onDispose { onObserverRemoved() }
    }
    return state.collectAsState()
}
