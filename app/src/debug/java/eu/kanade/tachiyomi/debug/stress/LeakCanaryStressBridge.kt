package eu.kanade.tachiyomi.debug.stress

import leakcanary.AppWatcher
import leakcanary.EventListener
import leakcanary.LeakCanary
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess

/**
 * Hands LeakCanary's heap analyses to a stress run, which records every leak it finds. Debug builds only, since that
 * is the only build LeakCanary is in; [StressRunner] finds it by name.
 */
@Suppress("unused")
object LeakCanaryStressBridge {

    private var listening = false

    /** Adds the run's listener once; [dumpHeap] is set for every run, since one process can hold runs of each mode. */
    @JvmStatic
    @Synchronized
    fun install(onRecord: (Map<String, Any?>) -> Unit, dumpHeap: Boolean) {
        LeakCanary.config = LeakCanary.config.run {
            copy(
                dumpHeap = dumpHeap,
                eventListeners = if (listening) eventListeners else eventListeners + listener(onRecord),
            )
        }
        listening = true
    }

    /** Objects that should have been collected by now and are still reachable. */
    @JvmStatic
    fun retainedObjects(): Int = AppWatcher.objectWatcher.retainedObjectCount

    private fun listener(onRecord: (Map<String, Any?>) -> Unit) = EventListener { event ->
        if (event !is EventListener.Event.HeapAnalysisDone<*>) return@EventListener
        when (val analysis = event.heapAnalysis) {
            is HeapAnalysisSuccess -> analysis.applicationLeaks.forEach { leak ->
                onRecord(
                    mapOf(
                        "kind" to "leak",
                        "signature" to leak.signature,
                        "description" to leak.shortDescription,
                        "retainedBytes" to leak.totalRetainedHeapByteSize,
                        "instances" to leak.leakTraces.size,
                        "heapDump" to analysis.heapDumpFile.path,
                    ),
                )
            }
            is HeapAnalysisFailure -> onRecord(
                mapOf("kind" to "leak_analysis_failed", "error" to analysis.exception.toString()),
            )
        }
    }
}
