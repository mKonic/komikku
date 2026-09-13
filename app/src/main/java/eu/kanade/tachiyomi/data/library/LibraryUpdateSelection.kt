package eu.kanade.tachiyomi.data.library

import android.content.Context
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.UUID

/**
 * The entries a "refresh selected" update is limited to. WorkManager input data is capped at 10 KB, room for about
 * 1,270 ids, so a larger selection never started. The ids go in a file instead, named by a tag on the work.
 */
internal object LibraryUpdateSelection {

    private const val TAG_PREFIX = "LibraryUpdate-selection:"

    fun dir(context: Context) = File(context.noBackupFilesDir, "library_update_selection")

    fun tag(name: String) = TAG_PREFIX + name

    /** The selection one of [tags] names, or null when the work is not limited to one. */
    fun nameIn(tags: Collection<String>): String? =
        tags.firstOrNull { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)

    /** Writes [ids] to a new file in [dir] and returns its name. */
    fun write(dir: File, ids: Collection<Long>): String {
        dir.mkdirs()
        val name = UUID.randomUUID().toString()
        File(dir, name).sink().buffer().use { sink -> ids.forEach { sink.writeLong(it) } }
        return name
    }

    /** The ids of selection [name]; empty when its file is gone, so a lost selection never widens to the library. */
    fun read(dir: File, name: String): Set<Long> {
        val file = File(dir, name)
        if (!file.exists()) return emptySet()
        return file.source().buffer().use { source ->
            buildSet { while (!source.exhausted()) add(source.readLong()) }
        }
    }

    /** Deletes every selection file that none of [tags], the tags of queued or running updates, names. */
    fun prune(dir: File, tags: Collection<String>) {
        val keep = tags.filter { it.startsWith(TAG_PREFIX) }.map { it.removePrefix(TAG_PREFIX) }.toSet()
        dir.listFiles()?.filterNot { it.name in keep }?.forEach { it.delete() }
    }
}
