package mihon.core.screenmodel

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ObservationSignalTest {

    private val timeout = 5.seconds

    @Test
    fun `starts unobserved and turns on as soon as an observer arrives`() = runTest {
        val observers = MutableStateFlow(0)
        val seen = mutableListOf<Boolean>()

        val job = launch { observers.observationSignal(timeout).toList(seen) }
        advanceTimeBy(timeout + 1.seconds)

        observers.value = 1
        advanceTimeBy(1.milliseconds)

        // No wait on the arriving edge - the screen must not sit blank for the timeout.
        seen shouldContainExactly listOf(false, true)
        job.cancel()
    }

    @Test
    fun `keeps running for the timeout after the last observer leaves`() = runTest {
        val observers = MutableStateFlow(1)
        val seen = mutableListOf<Boolean>()

        val job = launch { observers.observationSignal(timeout).toList(seen) }
        advanceTimeBy(1.milliseconds)
        seen shouldContainExactly listOf(true)

        observers.value = 0
        advanceTimeBy(timeout - 1.seconds)
        // Still running: the grace period has not elapsed.
        seen shouldContainExactly listOf(true)

        advanceTimeBy(2.seconds)
        seen shouldContainExactly listOf(true, false)
        job.cancel()
    }

    @Test
    fun `a quick switch away and back never stops collection`() = runTest {
        val observers = MutableStateFlow(1)
        val seen = mutableListOf<Boolean>()

        val job = launch { observers.observationSignal(timeout).toList(seen) }
        advanceTimeBy(1.milliseconds)

        // The shape of a rotation, or a glance at another tab.
        observers.value = 0
        advanceTimeBy(1.seconds)
        observers.value = 1
        advanceTimeBy(timeout + 1.seconds)

        // One `true` and nothing else: the pipeline was never torn down or rebuilt.
        seen shouldContainExactly listOf(true)
        job.cancel()
    }

    @Test
    fun `two observers keep it open until both leave`() = runTest {
        val observers = MutableStateFlow(0)
        val seen = mutableListOf<Boolean>()

        val job = launch { observers.observationSignal(timeout).toList(seen) }
        advanceTimeBy(timeout + 1.seconds)

        observers.value = 1
        observers.value = 2
        advanceTimeBy(1.seconds)
        observers.value = 1
        advanceTimeBy(timeout + 1.seconds)

        // Dropping from two to one is not "unobserved".
        seen shouldContainExactly listOf(false, true)

        observers.value = 0
        advanceTimeBy(timeout + 1.seconds)
        seen shouldContainExactly listOf(false, true, false)
        job.cancel()
    }

    @Test
    fun `an unbalanced remove cannot drive the count negative`() = runTest {
        val model = object : ObservedStateScreenModel<Int>(0) {}
        val seen = mutableListOf<Boolean>()

        model.onObserverRemoved()
        model.onObserverRemoved()
        model.onObserverAdded()

        val job = launch { model.observers().observationSignal(timeout).toList(seen) }
        advanceTimeBy(1.milliseconds)

        // Had the count gone to -2, this one add would leave it at -1 and the screen would stay
        // blank forever.
        seen shouldContainExactly listOf(true)
        job.cancel()
    }
}
