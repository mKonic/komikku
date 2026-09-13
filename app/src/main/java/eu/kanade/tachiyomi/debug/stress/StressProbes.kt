package eu.kanade.tachiyomi.debug.stress

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Measurements of the running process for the stress journal. */
internal object StressProbes {

    /** Cheap enough for every heartbeat: nothing is collected first, so the heaps include unreached garbage. */
    fun memory(): Map<String, Any?> {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val status = procStatus()
        return mapOf(
            "pssKb" to info.totalPss,
            "rssKb" to status["VmRSS"],
            "nativeKb" to info.getMemoryStat("summary.native-heap")?.toIntOrNull(),
            "javaKb" to info.getMemoryStat("summary.java-heap")?.toIntOrNull(),
            "graphicsKb" to info.getMemoryStat("summary.graphics")?.toIntOrNull(),
            "nativeAllocatedKb" to Debug.getNativeHeapAllocatedSize() / 1024,
            "threads" to status["Threads"],
            "fds" to File("/proc/self/fd").list()?.size,
        )
    }

    /** What is still held once the collector has run: the numbers to compare between passes. */
    fun settledMemory(): Map<String, Any?> {
        Runtime.getRuntime().gc()
        Runtime.getRuntime().gc()
        return memory()
    }

    /**
     * How long the main thread took to get to a task posted to it. A busy or blocked main thread shows up here
     * long before an ANR does; null means it did not get there within [timeoutMillis].
     */
    suspend fun mainThreadLagMillis(timeoutMillis: Long): Long? {
        val posted = SystemClock.uptimeMillis()
        val ran = CompletableDeferred<Long>()
        Handler(Looper.getMainLooper()).post { ran.complete(SystemClock.uptimeMillis() - posted) }
        return withTimeoutOrNull(timeoutMillis) { ran.await() }
    }

    fun mainThreadStack(): String = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "  at $it" }

    /** Every thread's name, state and top frames: what a stuck pass is blocked on is rarely the main thread. */
    fun threadStacks(): String = Thread.getAllStackTraces().entries
        .sortedBy { it.key.name }
        .joinToString("\n\n") { (thread, frames) ->
            "\"${thread.name}\" ${thread.state}\n" + frames.take(MAX_FRAMES).joinToString("\n") { "  at $it" }
        }

    private const val MAX_FRAMES = 48

    /** Numeric kB/count fields of /proc/self/status. */
    private fun procStatus(): Map<String, Long> = runCatching {
        File("/proc/self/status").readLines().mapNotNull { line ->
            val (key, value) = line.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            value.trim().substringBefore(' ').toLongOrNull()?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}
