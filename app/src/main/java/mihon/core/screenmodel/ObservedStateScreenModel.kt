package mihon.core.screenmodel

import cafe.adriel.voyager.core.model.StateScreenModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [StateScreenModel] that knows whether its screen is currently on screen, so expensive upstream
 * flows can stop while it is not.
 *
 * Tab screen models outlive the tab being selected, and a pushed screen leaves the tabs beneath it
 * alive, so a database-backed pipeline started in `init` keeps recomputing for screens nobody is
 * looking at - re-filtering and re-sorting the whole library on every write a library update makes,
 * for instance.
 *
 * The obvious signal, `state.subscriptionCount`, does not work here: these models collect their own
 * `state` internally to react to search queries and filters, so the count never falls to zero and
 * the gate would latch shut. Observation is therefore reported explicitly by the composable that
 * reads the state - see `collectAsStateWhileObserved`.
 *
 * [StateScreenModel.state] itself is deliberately left alone: it is `final` in Voyager, and callers
 * keep reading it exactly as before. Only what feeds it is gated.
 */
abstract class ObservedStateScreenModel<S>(initialState: S) : StateScreenModel<S>(initialState) {

    private val observers = MutableStateFlow(0)

    fun onObserverAdded() {
        observers.update { it + 1 }
    }

    fun onObserverRemoved() {
        observers.update { (it - 1).coerceAtLeast(0) }
    }

    /** Exposed for tests; the count is otherwise nobody's business but [whileObserved]'s. */
    internal fun observers(): Flow<Int> = observers

    /**
     * Collects this flow only while the screen is being observed.
     *
     * After the last observer leaves, collection continues for [timeout] before stopping, so a
     * rotation or a quick trip to another tab does not tear the pipeline down and rebuild it.
     */
    protected fun <T> Flow<T>.whileObserved(timeout: Duration = DEFAULT_TIMEOUT): Flow<T> =
        observers.observationSignal(timeout).flatMapLatest { observed ->
            if (observed) this else emptyFlow()
        }

    companion object {
        val DEFAULT_TIMEOUT = 5.seconds
    }
}

/**
 * Turns an observer count into an "is being observed" signal, held open for [timeout] after the
 * last observer leaves.
 *
 * Separate from the class so the timing can be tested without a screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Int>.observationSignal(
    timeout: Duration = ObservedStateScreenModel.DEFAULT_TIMEOUT,
): Flow<Boolean> = map { it > 0 }
    .distinctUntilChanged()
    .transformLatest { observed ->
        // Only the leaving edge waits. transformLatest cancels this delay if an observer arrives
        // in the meantime, which is what makes a quick switch away and back free.
        if (!observed) delay(timeout)
        emit(observed)
    }
    .distinctUntilChanged()
