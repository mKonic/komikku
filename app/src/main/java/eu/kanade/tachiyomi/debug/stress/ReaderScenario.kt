package eu.kanade.tachiyomi.debug.stress

import eu.kanade.tachiyomi.ui.reader.soak.ReaderSoakTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The reader soak, one chapter more each pass: every page of the first chapters of one series per reading mode,
 * turned as soon as it is on screen. Its heap dump is left off; LeakCanary watches the whole run instead.
 */
object ReaderScenario : StressScenario {
    override val name = "reader"

    override suspend fun run(context: StressContext, iteration: Int) {
        val series = ReaderSoakTest.pickSeries()
        if (series.isEmpty()) throw StressSkip("no series from an installed source in the library")

        val end = coroutineScope {
            val ending = async(start = CoroutineStart.UNDISPATCHED) { ReaderSoakTest.ends.first() }
            ReaderSoakTest.start(context.app, series, chaptersPerSeries = 1 + iteration, rounds = 1, heapDump = false)
            var seen = 0L
            while (!ending.isCompleted) {
                val step = ReaderSoakTest.lastStepAt
                if (step != seen) {
                    seen = step
                    context.alive()
                }
                delay(PROGRESS_POLL_MILLIS)
            }
            ending.await()
        }
        context.step("soak_end", mapOf("end" to end, "csv" to ReaderSoakTest.lastOutput?.path))
        if (end != "done") error("the reader soak ended with $end")
    }

    private const val PROGRESS_POLL_MILLIS = 1_000L
}
