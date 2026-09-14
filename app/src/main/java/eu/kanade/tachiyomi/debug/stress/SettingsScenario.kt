package eu.kanade.tachiyomi.debug.stress

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.delay
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.reflect.Method

/**
 * Changes reader settings while the rest of the run is using them.
 *
 * Every other scenario reads with whatever the settings happen to be; nothing was changing them under a page that was
 * already on screen. That is where a viewer that reads a preference once in `init`, or rebuilds itself without letting
 * go of what it held, gives itself away.
 *
 * The knobs are taken from [ReaderPreferences] by reflection rather than listed here, so a preference added later is
 * covered without anyone remembering to add it. Values are restored at the end of a pass, including when the pass is
 * cancelled; the originals are journalled first, so a process killed mid-pass still leaves a record to put them back.
 */
object SettingsScenario : StressScenario {
    override val name = "settings"

    /** Per pass, so a change has time to reach the screen and so the journal can tell which one did something. */
    private const val PER_PASS = 8
    private const val SETTLE_MILLIS = 1_500L

    private val preferences by lazy { Injekt.get<ReaderPreferences>() }

    /** Every no-argument method that hands back a preference, in a stable order so a pass can walk them in turn. */
    private val knobs: List<Method> by lazy {
        ReaderPreferences::class.java.methods
            .filter { it.parameterCount == 0 && Preference::class.java.isAssignableFrom(it.returnType) }
            .sortedBy { it.name }
    }

    override suspend fun run(context: StressContext, iteration: Int) {
        if (knobs.isEmpty()) throw StressSkip("no reader preferences found to change")

        // A window that moves along each pass, so a long run covers all of them rather than the same few.
        val start = (iteration * PER_PASS) % knobs.size
        val window = List(minOf(PER_PASS, knobs.size)) { knobs[(start + it) % knobs.size] }

        val original = mutableListOf<Pair<Preference<Any>, Any>>()
        try {
            for (method in window) {
                val preference = preference(method) ?: continue
                val was = preference.get() ?: continue
                val next = nextValue(was)
                if (next == null) {
                    context.step("skipped", mapOf("setting" to method.name, "type" to was.javaClass.simpleName))
                    continue
                }
                original += preference to was
                // Journalled before the change, so the values are recoverable even if this process does not return.
                context.step("change", mapOf("setting" to method.name, "from" to "$was", "to" to "$next"))
                preference.set(next)
                awaitUiSettled()
                delay(SETTLE_MILLIS)
            }
            context.step("changed", mapOf("count" to original.size))
        } finally {
            // Reversed, so a preference touched twice ends on the value it started with.
            original.asReversed().forEach { (preference, was) -> runCatching { preference.set(was) } }
            context.journal.record(
                "step",
                mapOf("scenario" to name, "event" to "restored", "count" to original.size),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun preference(method: Method): Preference<Any>? =
        runCatching { method.invoke(preferences) as? Preference<Any> }.getOrNull()

    /**
     * A different, still valid value for whatever this preference holds. Strings are left alone: they are paths and
     * URIs here, and a made up one says nothing about the app that a missing file does not already say.
     */
    private fun nextValue(current: Any): Any? = when (current) {
        is Boolean -> !current
        is Enum<*> -> {
            val constants = current.javaClass.enumConstants
            if (constants.isNullOrEmpty()) null else constants[(current.ordinal + 1) % constants.size]
        }
        // No range is declared anywhere, so this walks the small numbers the enum-like ones use and leaves the rest
        // to come back round. Anything that reads a preference it does not expect should cope rather than break.
        is Int -> (current + 1) % INT_CEILING
        is Long -> (current + 1) % INT_CEILING
        is Float -> if (current > 0f) 0f else 1f
        else -> null
    }

    private const val INT_CEILING = 8
}
