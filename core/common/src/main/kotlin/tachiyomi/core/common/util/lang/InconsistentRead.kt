package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Runs [read] again when it failed the way a database cursor does if its rows change while it is being read. Android
 * runs the query again for every CursorWindow, so a write between two windows leaves rows missing: generated mappers
 * then find null in a non-null column, or the window refuses the row. The next attempt nearly always reads a settled
 * table; a failure that keeps happening is rethrown after the last attempt.
 */
suspend fun <T> retryInconsistentRead(attempts: Int = INCONSISTENT_READ_ATTEMPTS, read: suspend () -> T): T {
    var attempt = 1
    while (true) {
        try {
            return read()
        } catch (e: RuntimeException) {
            if (attempt >= attempts || !e.isInconsistentCursorRead()) throw e
            InconsistentRead.logcat(LogPriority.WARN, e) { "Rows changed under the cursor, reading again (attempt $attempt)" }
            attempt++
        }
    }
}

private object InconsistentRead

private const val INCONSISTENT_READ_ATTEMPTS = 3

internal fun Throwable.isInconsistentCursorRead(): Boolean = when (this) {
    is CancellationException -> false
    is NullPointerException -> true
    is IllegalStateException -> message.orEmpty().contains("CursorWindow")
    else -> false
}
