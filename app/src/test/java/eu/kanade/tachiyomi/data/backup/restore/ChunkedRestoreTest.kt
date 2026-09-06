package eu.kanade.tachiyomi.data.backup.restore

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The batching exists for speed, so what needs covering is the part that keeps that speed from
 * costing data: one entry that cannot be restored must not take its chunk down with it.
 */
class ChunkedRestoreTest {

    /** Stands in for a database transaction: rolls its writes back if the body throws. */
    private class FakeDb {
        val committed = mutableListOf<String>()
        var transactions = 0

        suspend fun transaction(block: suspend () -> Unit) {
            transactions++
            val mark = committed.size
            try {
                block()
            } catch (e: Throwable) {
                while (committed.size > mark) committed.removeLast()
                throw e
            }
        }
    }

    @Test
    fun `restores everything and batches by chunk size`() = runTest {
        val db = FakeDb()
        val progress = mutableListOf<Int>()

        chunkedRestore(
            items = (1..10).map { "m$it" },
            chunkSize = 4,
            inTransaction = { db.transaction(it) },
            restore = { db.committed += it },
            onError = { _, _ -> error("should not be reached") },
            onChunkRestored = { restored, _ -> progress += restored },
        )

        db.committed shouldBe (1..10).map { "m$it" }
        // 10 items, chunks of 4 -> 3 transactions rather than 10.
        db.transactions shouldBe 3
        progress shouldContainExactly listOf(4, 8, 10)
    }

    @Test
    fun `a failing entry does not take the rest of its chunk with it`() = runTest {
        val db = FakeDb()
        val failures = mutableListOf<String>()

        chunkedRestore(
            items = listOf("a", "b", "poison", "d"),
            chunkSize = 4,
            inTransaction = { db.transaction(it) },
            restore = { if (it == "poison") error("cannot restore $it") else db.committed += it },
            onError = { item, _ -> failures += item },
            onChunkRestored = { _, _ -> },
        )

        // The batch rolled back, then the retry landed every entry except the bad one.
        db.committed shouldContainExactly listOf("a", "b", "d")
        failures shouldContainExactly listOf("poison")
    }

    @Test
    fun `progress still counts the entry that failed`() = runTest {
        var reported = 0

        chunkedRestore(
            items = listOf("a", "poison", "c"),
            chunkSize = 3,
            inTransaction = { it() },
            restore = { if (it == "poison") error("nope") },
            onError = { _, _ -> },
            onChunkRestored = { restored, _ -> reported = restored },
        )

        // Otherwise the bar stalls short of the total and the restore looks stuck.
        reported shouldBe 3
    }

    @Test
    fun `a later chunk still runs after an earlier one falls back`() = runTest {
        val db = FakeDb()

        chunkedRestore(
            items = listOf("a", "poison", "c", "d"),
            chunkSize = 2,
            inTransaction = { db.transaction(it) },
            restore = { if (it == "poison") error("nope") else db.committed += it },
            onError = { _, _ -> },
            onChunkRestored = { _, _ -> },
        )

        db.committed shouldContainExactly listOf("a", "c", "d")
    }

    @Test
    fun `reports the last entry of each chunk for the notification`() = runTest {
        val seen = mutableListOf<String>()

        chunkedRestore(
            items = listOf("a", "b", "c", "d", "e"),
            chunkSize = 2,
            inTransaction = { it() },
            restore = { },
            onError = { _, _ -> },
            onChunkRestored = { _, last -> seen += last },
        )

        seen shouldContainExactly listOf("b", "d", "e")
    }

    @Test
    fun `cancellation is not swallowed and retried`() = runTest {
        var restoreCalls = 0

        assertThrows<CancellationException> {
            chunkedRestore(
                items = listOf("a", "b"),
                chunkSize = 2,
                inTransaction = { it() },
                restore = {
                    restoreCalls++
                    throw CancellationException("restore cancelled")
                },
                onError = { _, _ -> error("cancellation must not be recorded as an entry error") },
                onChunkRestored = { _, _ -> error("cancelled chunk must not report progress") },
            )
        }

        // Exactly one attempt: a cancelled restore must not be retried entry by entry.
        restoreCalls shouldBe 1
    }

    @Test
    fun `does nothing when there is nothing to restore`() = runTest {
        var chunks = 0

        chunkedRestore<String>(
            items = emptyList(),
            chunkSize = 10,
            inTransaction = { it() },
            restore = { },
            onError = { _, _ -> },
            onChunkRestored = { _, _ -> chunks++ },
        )

        chunks shouldBe 0
    }
}
