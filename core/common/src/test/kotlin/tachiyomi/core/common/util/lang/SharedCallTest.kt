package tachiyomi.core.common.util.lang

import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedCallTest {

    private val runs = AtomicInteger()
    private val gate = CompletableDeferred<Unit>()
    private val call = SharedCall(CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        val run = runs.incrementAndGet()
        gate.await()
        run
    }

    @Test
    fun `callers that arrive while it runs share one run`() = runBlocking<Unit> {
        // Undispatched, each caller has reached await() before the next one starts and before the gate opens.
        val callers = List(10) { async(start = CoroutineStart.UNDISPATCHED) { call.await() } }
        gate.complete(Unit)

        callers.awaitAll() shouldContainOnly listOf(1)
        runs.get() shouldBe 1
    }

    @Test
    fun `a call after the run finished runs again`() = runBlocking<Unit> {
        gate.complete(Unit)

        call.await() shouldBe 1
        call.await() shouldBe 2
    }

    @Test
    fun `a cancelled caller does not cancel the run for the others`() = runBlocking<Unit> {
        val leaving = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        val staying = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        leaving.cancel()
        gate.complete(Unit)

        staying.await() shouldBe 1
        runs.get() shouldBe 1
    }
}
