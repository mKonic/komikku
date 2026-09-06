package mihon.core.screenmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Reads the model's state and, while this composable is in composition *and* its lifecycle is at
 * least started, tells the model it is being observed so its gated flows run.
 *
 * Use this instead of `screenModel.state.collectAsState()` on any [ObservedStateScreenModel]:
 * without it the model is never marked observed and its gated pipelines never start, which shows up
 * immediately as a screen stuck on its initial state.
 *
 * The lifecycle half is not decoration. A stopped activity keeps its composition, so a
 * composition-only signal stays on while the app is in the background - and the reader is its own
 * activity, which means the tabs underneath would otherwise keep recomputing for the whole time
 * someone is reading, against the chapter-progress writes that reading generates.
 */
@Composable
fun <S> ObservedStateScreenModel<S>.collectAsStateWhileObserved(): State<S> {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(this, lifecycleOwner) {
        // Kept exactly balanced: adding the observer replays ON_START when already started, and
        // removing it dispatches nothing, so disposal has to account for its own decrement.
        var counted = false

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!counted) {
                    counted = true
                    onObserverAdded()
                }
                Lifecycle.Event.ON_STOP -> if (counted) {
                    counted = false
                    onObserverRemoved()
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (counted) {
                counted = false
                onObserverRemoved()
            }
        }
    }

    return state.collectAsStateWithLifecycle()
}
