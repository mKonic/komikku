package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Runs [block] once for every caller that arrives while it is running: they wait for the same result instead of
 * starting their own run. The work runs in [scope], so a caller that is cancelled stops waiting without cancelling it
 * for the others, and a call after it finished runs [block] again.
 */
class SharedCall<T>(
    private val scope: CoroutineScope,
    private val block: suspend () -> T,
) {
    private val lock = Any()
    private var running: Deferred<T>? = null

    suspend fun await(): T {
        val deferred = synchronized(lock) {
            running ?: scope.async { block() }.also { started ->
                running = started
                started.invokeOnCompletion {
                    synchronized(lock) { if (running === started) running = null }
                }
            }
        }
        return deferred.await()
    }
}
