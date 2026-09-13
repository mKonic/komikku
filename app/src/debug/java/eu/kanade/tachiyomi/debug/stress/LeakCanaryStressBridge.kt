package eu.kanade.tachiyomi.debug.stress

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

    @JvmStatic
    fun install(onRecord: (Map<String, Any?>) -> Unit) {
        LeakCanary.config = LeakCanary.config.run {
            copy(
                eventListeners = eventListeners + EventListener { event ->
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
                },
            )
        }
    }
}
