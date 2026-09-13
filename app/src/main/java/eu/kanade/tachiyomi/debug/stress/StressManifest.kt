package eu.kanade.tachiyomi.debug.stress

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * What a stress run is and where it stands, rewritten whole and atomically: a power cut leaves either the previous
 * manifest or the new one, never a mix.
 */
@Serializable
data class StressManifest(
    val runId: String,
    val mode: StressMode,
    val scenarios: List<String>,
    val network: Boolean,
    val rounds: Int,
    val startedAt: Long,
    val build: String,
    val heartbeatMillis: Long,
    /** The process lifetime writing now; each start of the process while the run is active takes the next one. */
    val session: Int = 1,
    val active: Boolean = true,
    /** Newest system exit record already journaled, so a restart reports each death once. */
    val lastExitTimestamp: Long = 0,
    val stopReason: String? = null,
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun read(file: File): StressManifest? =
            runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull()

        fun write(file: File, manifest: StressManifest) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use {
                it.write(json.encodeToString(serializer(), manifest).toByteArray())
                it.fd.sync()
            }
            check(tmp.renameTo(file)) { "Could not replace $file" }
            file.parentFile?.let(::syncDirectory)
        }
    }
}

@Serializable
enum class StressMode {
    /** Every scenario in turn, the given number of rounds. */
    ONCE,

    /** Every scenario at the same time, each the given number of rounds. */
    OVERLOAD,

    /** Overload that never ends, and picks itself back up after the process dies. */
    ETERNAL,
}
