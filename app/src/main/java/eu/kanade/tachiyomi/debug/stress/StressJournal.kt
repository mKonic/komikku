package eu.kanade.tachiyomi.debug.stress

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The record of a stress run, built to outlive the process dying at any point and the machine losing power.
 *
 * Records are JSON lines in segment files named after the process lifetime ([session]) that wrote them, and no
 * later lifetime opens them again: a line torn by a crash or power cut stays at the end of its own segment instead
 * of running into the next record. A writer thread takes whatever has queued up, writes it, and fsyncs before it
 * takes more, so a power cut costs at most the batch that was in flight. [writeNow] skips the queue for the last
 * words of a dying process.
 */
class StressJournal(
    private val dir: File,
    private val session: Int,
    private val segmentBytes: Long = SEGMENT_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

    private val lock = Any()
    private val queue = LinkedBlockingQueue<String>()
    private val seq = AtomicLong()
    private val unwritten = AtomicLong()

    /** One permit per queued record; the writer wakes on it but takes records only under [lock]. */
    private val work = Semaphore(0)

    private var segment = 0
    private var out: FileOutputStream? = null
    private var segmentWritten = 0L

    @Volatile
    private var closed = false

    // Last, so the thread only starts once everything it touches exists.
    private val writer = Thread(::drain, "StressJournal-$session").apply {
        isDaemon = true
        start()
    }

    /** Queues a record; returns at once. */
    fun record(kind: String, fields: Map<String, Any?> = emptyMap()) {
        if (closed) return
        unwritten.incrementAndGet()
        queue.put(encode(kind, fields))
        work.release()
    }

    /** Writes everything queued, then this record, and syncs before returning. For a process about to die. */
    fun writeNow(kind: String, fields: Map<String, Any?> = emptyMap()) {
        val line = encode(kind, fields)
        synchronized(lock) {
            val pending = ArrayList<String>()
            queue.drainTo(pending)
            pending += line
            writeLines(pending)
            unwritten.addAndGet(-(pending.size - 1).toLong())
        }
    }

    /** Blocks until every queued record is on disk, or [timeoutMillis] passes. */
    fun flush(timeoutMillis: Long = 10_000) {
        synchronized(lock) {
            val pending = ArrayList<String>()
            queue.drainTo(pending)
            writeLines(pending)
            unwritten.addAndGet(-pending.size.toLong())
        }
        val deadline = clock() + timeoutMillis
        while (unwritten.get() > 0 && clock() < deadline) Thread.sleep(FLUSH_POLL_MILLIS)
    }

    override fun close() {
        flush()
        closed = true
        writer.join(WRITER_JOIN_MILLIS)
        synchronized(lock) {
            out?.close()
            out = null
        }
    }

    private fun drain() {
        val batch = ArrayList<String>()
        while (true) {
            if (!work.tryAcquire(POLL_SECONDS, TimeUnit.SECONDS)) {
                if (closed) return
                continue
            }
            work.drainPermits()
            // Taken and written under the lock, so writeNow never passes a record that was taken but not yet written.
            synchronized(lock) {
                queue.drainTo(batch)
                writeLines(batch)
            }
            unwritten.addAndGet(-batch.size.toLong())
            batch.clear()
        }
    }

    /** Callers hold [lock]. Rotates between lines, so a backlog written at once still leaves every segment near its size. */
    private fun writeLines(lines: List<String>) {
        var from = 0
        while (from < lines.size) {
            val stream = out ?: openSegment()
            val chunk = StringBuilder()
            var until = from
            while (until < lines.size && (until == from || segmentWritten + chunk.length < segmentBytes)) {
                chunk.append(lines[until]).append('\n')
                until++
            }
            val bytes = chunk.toString().toByteArray()
            stream.write(bytes)
            stream.fd.sync()
            segmentWritten += bytes.size
            if (segmentWritten >= segmentBytes) {
                stream.close()
                out = null
            }
            from = until
        }
    }

    private fun openSegment(): FileOutputStream {
        dir.mkdirs()
        var file: File
        do {
            segment++
            file = File(dir, "journal-%04d-%04d.jsonl".format(session, segment))
        } while (file.exists())
        return FileOutputStream(file, true).also {
            out = it
            segmentWritten = 0
            syncDirectory(dir)
        }
    }

    private fun encode(kind: String, fields: Map<String, Any?>): String {
        val content = LinkedHashMap<String, JsonElement>()
        content["t"] = JsonPrimitive(clock())
        content["session"] = JsonPrimitive(session)
        content["seq"] = JsonPrimitive(seq.incrementAndGet())
        content["kind"] = JsonPrimitive(kind)
        fields.forEach { (key, value) -> content[key] = value.toJson() }
        return JsonObject(content).toString()
    }

    companion object {
        /** A segment is closed past this size; it bounds each file, not the run. */
        const val SEGMENT_BYTES = 4L * 1024 * 1024
        private const val POLL_SECONDS = 1L
        private const val FLUSH_POLL_MILLIS = 5L
        private const val WRITER_JOIN_MILLIS = 5_000L
    }
}

internal fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Throwable -> JsonPrimitive(stackTraceToString())
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJson() })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Array<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}

/** Makes a file created or renamed in [dir] survive a power cut. Best effort: not every filesystem allows it. */
internal fun syncDirectory(dir: File) {
    runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
}
