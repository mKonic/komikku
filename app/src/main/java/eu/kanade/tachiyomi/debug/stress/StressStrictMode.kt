package eu.kanade.tachiyomi.debug.stress

import android.os.Build
import android.os.StrictMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * StrictMode, routed into the journal for the length of a stress run.
 *
 * The value here is that it finds things no scenario has to think of: disk or network on the main thread, a cursor or
 * a stream nobody closed, an activity the framework can still see after it should be gone. A scenario only finds what
 * it was written to look for; this reports whatever the app does wrong while any of them runs.
 *
 * Everything is reported rather than enforced. A penalty that kills the process would end the run on the first
 * violation, and the point of a long run is to collect all of them.
 */
internal object StressStrictMode {

    /** One line per distinct violation, so a main-thread read in a hot loop does not fill the journal. */
    private val seen = ConcurrentHashMap.newKeySet<String>()

    /** Violations arrive on the offending thread; writing the journal there would itself be disk on that thread. */
    private val reporter = Executors.newSingleThreadExecutor { Thread(it, "StressStrictMode") }

    fun install(journal: StressJournal) {
        // The listener API is what makes this reportable instead of fatal; below it, there is nothing to attach to.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        seen.clear()

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyListener(reporter) { violation -> report(journal, "thread", violation) }
                .build(),
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyListener(reporter) { violation -> report(journal, "vm", violation) }
                .build(),
        )
    }

    /** Back to whatever the app runs with normally, so a stopped run leaves no policy behind. */
    fun remove() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        seen.clear()
    }

    private fun report(journal: StressJournal, policy: String, violation: Violation) {
        // The top frames inside the app are what identifies it; the framework above them is the same every time.
        val appFrames = violation.stackTrace.filter { it.className.startsWith(APP_PACKAGE) }
        // The harness reads its own journal directory from the main thread when a run starts. That is this file's
        // doing, not the app's, and reporting it would put the same line in every report forever.
        if (appFrames.firstOrNull()?.className?.startsWith(HARNESS_PACKAGE) == true) return
        val frames = appFrames.take(MAX_FRAMES).joinToString("\n") { "  at $it" }
        val kind = violation.javaClass.simpleName
        // By method rather than by line, with a method that calls itself counted once: a recursive walk of a
        // directory reports from a different line each time round, and all of them are the one thing to fix.
        val methods = appFrames.map { "${it.className}.${it.methodName}" }
        val signature = methods
            .filterIndexed { index, method -> index == 0 || methods[index - 1] != method }
            .take(MAX_FRAMES)
            .joinToString("\n", prefix = "$kind\n")
        if (!seen.add(signature)) return

        journal.record(
            "strict",
            mapOf(
                "policy" to policy,
                "violation" to kind,
                "where" to frames.ifEmpty { "(no app frames)" },
            ),
        )
    }

    private const val APP_PACKAGE = "eu.kanade"
    private const val HARNESS_PACKAGE = "eu.kanade.tachiyomi.debug.stress"
    private const val MAX_FRAMES = 8
}

private typealias Violation = android.os.strictmode.Violation
