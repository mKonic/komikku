package eu.kanade.tachiyomi.debug.stress

import android.app.Application
import android.os.Looper
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One kind of load a stress run puts on the app, through the same code the app's own screens and jobs use. */
interface StressScenario {
    val name: String

    /** Scenarios that reach sources over the network only run when the run allows it. */
    val needsNetwork: Boolean get() = false

    /**
     * One pass. [iteration] counts the passes this process has run, so a pass can grow as the run goes on. A pass
     * that throws is recorded as a failure and the run carries on; [StressSkip] records that there was nothing to do.
     */
    suspend fun run(context: StressContext, iteration: Int)
}

class StressSkip(reason: String) : Exception(reason)

class StressContext(
    val app: Application,
    val journal: StressJournal,
    val scenario: String,
    val network: Boolean,
    private val onProgress: () -> Unit,
) {
    /** Records a step and counts as progress for the stall watchdog. */
    fun step(event: String, fields: Map<String, Any?> = emptyMap()) {
        onProgress()
        journal.record("step", mapOf("scenario" to scenario, "event" to event) + fields)
    }

    /** Counts as progress without a record, for long work that is still moving. */
    fun alive() = onProgress()

    /** The screen stack, once the main activity is up; it may be recreated between passes. */
    suspend fun navigator(): Navigator {
        while (true) {
            StressHooks.navigator?.let { return it }
            delay(ACTIVITY_POLL_MILLIS)
        }
    }

    private companion object {
        const val ACTIVITY_POLL_MILLIS = 500L
    }
}

/**
 * Waits for the UI to settle after a change: two frames drawn, then the main thread's queue empty. Returns false if
 * it has not settled after [timeoutMillis], which is worth recording but is not a failure by itself.
 */
suspend fun awaitUiSettled(timeoutMillis: Long = UI_SETTLE_TIMEOUT_MILLIS): Boolean = withContext(Dispatchers.Main) {
    withTimeoutOrNull(timeoutMillis) {
        awaitFrame()
        awaitFrame()
        while (!Looper.getMainLooper().queue.isIdle) delay(UI_IDLE_POLL_MILLIS)
        true
    } ?: false
}

private const val UI_SETTLE_TIMEOUT_MILLIS = 30_000L
private const val UI_IDLE_POLL_MILLIS = 16L
