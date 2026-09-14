package tachiyomi.core.common.util.lang

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class InconsistentReadTest {

    @Test
    fun `a read that lost rows to a concurrent write succeeds on a later attempt`() = runBlocking<Unit> {
        var calls = 0
        val result = retryInconsistentRead {
            calls++
            if (calls < 3) throw NullPointerException() else listOf("row")
        }

        result shouldBe listOf("row")
        calls shouldBe 3
    }

    @Test
    fun `a cursor window refusing a row is read again`() = runBlocking<Unit> {
        var calls = 0
        retryInconsistentRead {
            calls++
            if (calls == 1) throw IllegalStateException("Couldn't read row 1230, col 0 from CursorWindow.") else 1
        } shouldBe 1
    }

    @Test
    fun `a failure that keeps happening is rethrown after the last attempt`() {
        var calls = 0
        shouldThrow<NullPointerException> {
            runBlocking {
                retryInconsistentRead(attempts = 3) {
                    calls++
                    throw NullPointerException()
                }
            }
        }
        calls shouldBe 3
    }

    @Test
    fun `other failures are not retried`() {
        var calls = 0
        shouldThrow<IllegalStateException> {
            runBlocking {
                retryInconsistentRead {
                    calls++
                    throw IllegalStateException("database is locked")
                }
            }
        }
        calls shouldBe 1
    }
}
