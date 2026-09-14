package eu.kanade.tachiyomi.debug.stress

import android.content.ComponentCallbacks2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis

/**
 * Throws away the activity and puts the app under memory pressure, which is what the system does on its own and what
 * nothing else in the run was doing.
 *
 * A rotation, a theme change or a language change all destroy the activity and build a new one while the process
 * lives on. That is where state kept in the wrong place goes missing, and where anything still holding the old
 * activity turns into a leak; LeakCanary is watching, so an activity that survives its own replacement is reported.
 * The trim callbacks are the other half: caches are meant to let go when asked, and a cache that grows back the moment
 * it is cleared shows up in the heartbeat's memory line rather than as a failure.
 */
object ConfigChurnScenario : StressScenario {
    override val name = "config"

    /** Levels the system actually sends, from a gentle nudge to what a backgrounded app gets before it is killed. */
    private val trimLevels = listOf(
        "running_moderate" to ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
        "running_low" to ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        "running_critical" to ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        "ui_hidden" to ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
        "background" to ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
    )

    override suspend fun run(context: StressContext, iteration: Int) {
        val activity = StressHooks.activity ?: throw StressSkip("no activity to recreate")

        val recreateMs = measureTimeMillis {
            withContext(Dispatchers.Main) { activity.recreate() }
            // The new activity registers itself, so this waits for one that is not the one just thrown away.
            val replaced = withTimeoutOrNull(RECREATE_TIMEOUT_MILLIS) {
                while (StressHooks.activity.let { it == null || it === activity }) delay(ACTIVITY_POLL_MILLIS)
                true
            }
            if (replaced == null) error("the activity did not come back ${RECREATE_TIMEOUT_MILLIS / 1000} s after recreate")
        }
        val settled = awaitUiSettled()
        context.step("recreate", mapOf("recreateMs" to recreateMs, "settled" to settled))

        // One level per pass rather than all of them at once, so the memory line after the pass is attributable.
        val (label, level) = trimLevels[iteration % trimLevels.size]
        withContext(Dispatchers.Main) { context.app.onTrimMemory(level) }
        delay(SETTLE_MILLIS)
        context.step("trim", mapOf("level" to label) + StressProbes.memory())

        // The harshest callback the framework has, and the one caches are least likely to have been tested against.
        if (iteration % LOW_MEMORY_EVERY == LOW_MEMORY_EVERY - 1) {
            withContext(Dispatchers.Main) { context.app.onLowMemory() }
            delay(SETTLE_MILLIS)
            context.step("low_memory", StressProbes.memory())
        }
    }

    private const val RECREATE_TIMEOUT_MILLIS = 60_000L
    private const val ACTIVITY_POLL_MILLIS = 250L
    private const val SETTLE_MILLIS = 2_000L
    private const val LOW_MEMORY_EVERY = 5
}
