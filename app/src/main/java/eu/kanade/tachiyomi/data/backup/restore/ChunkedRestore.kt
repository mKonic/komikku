package eu.kanade.tachiyomi.data.backup.restore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Restores [items] a chunk at a time, each chunk under one transaction, falling back to restoring
 * that chunk entry by entry if the transaction fails.
 *
 * Batching is what makes a large restore fast - every entry otherwise commits several times over -
 * but it means one bad entry rolls the whole chunk back. The fallback is what keeps that from
 * turning a single unrestorable entry into a hundred silently missing ones.
 *
 * @param inTransaction runs its argument inside a database transaction
 * @param restore restores one entry; may throw
 * @param onError records an entry that could not be restored even on its own
 * @param onChunkRestored reports total progress after each chunk, with the chunk's last entry
 */
internal suspend fun <T> chunkedRestore(
    items: List<T>,
    chunkSize: Int,
    inTransaction: suspend (suspend () -> Unit) -> Unit,
    restore: suspend (T) -> Unit,
    onError: suspend (T, Throwable) -> Unit,
    onChunkRestored: suspend (restored: Int, last: T) -> Unit,
    onChunkFailed: (Throwable) -> Unit = {},
) {
    var restored = 0

    items.chunked(chunkSize).forEach { chunk ->
        currentCoroutineContext().ensureActive()

        val restoredAsChunk = try {
            inTransaction {
                chunk.forEach {
                    currentCoroutineContext().ensureActive()
                    restore(it)
                }
            }
            true
        } catch (e: CancellationException) {
            // Never retried: a cancelled restore has to stop, and catching this alongside real
            // failures would quietly turn a cancellation into a full entry-by-entry re-run.
            throw e
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            onChunkFailed(e)
            false
        }

        if (restoredAsChunk) {
            restored += chunk.size
        } else {
            chunk.forEach {
                currentCoroutineContext().ensureActive()
                try {
                    restore(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    onError(it, e)
                }
                restored += 1
            }
        }

        onChunkRestored(restored, chunk.last())
    }
}
