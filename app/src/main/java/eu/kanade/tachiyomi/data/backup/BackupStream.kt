package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import java.io.IOException

/**
 * A backup is one protobuf message whose field 1 repeats once per entry. Reading and writing it a field at a time
 * keeps one entry in memory instead of the whole library (mihonapp/mihon#3850).
 *
 * Fields are parsed by wire type rather than assumed to come in any order, so a file whose entries are not all at
 * the front still reads whole.
 */
internal object BackupStream {

    @PublishedApi internal const val MANGA_FIELD = 1

    @PublishedApi internal const val WIRE_VARINT = 0

    @PublishedApi internal const val WIRE_FIXED64 = 1

    @PublishedApi internal const val WIRE_LENGTH_DELIMITED = 2

    @PublishedApi internal const val WIRE_FIXED32 = 5

    /** Calls [onManga] with the encoded bytes of every entry, in file order. */
    inline fun forEachManga(source: BufferedSource, onManga: (ByteArray) -> Unit) {
        while (!source.exhausted()) {
            val tag = source.readVarLong()
            val wire = (tag and 7).toInt()
            if ((tag ushr 3).toInt() == MANGA_FIELD && wire == WIRE_LENGTH_DELIMITED) {
                onManga(source.readByteArray(source.readVarLong()))
            } else {
                skipField(source, wire, sink = null)
            }
        }
    }

    /** The number of entries, and everything else decoded as a [Backup] without them. */
    fun readMetadata(source: BufferedSource, parser: ProtoBuf): Pair<Int, Backup> {
        val rest = Buffer()
        var entries = 0
        while (!source.exhausted()) {
            val tag = source.readVarLong()
            val wire = (tag and 7).toInt()
            if ((tag ushr 3).toInt() == MANGA_FIELD && wire == WIRE_LENGTH_DELIMITED) {
                entries++
                source.skip(source.readVarLong())
            } else {
                rest.writeVarLong(tag)
                skipField(source, wire, sink = rest)
            }
        }
        return entries to parser.decodeFromByteArray(Backup.serializer(), rest.readByteArray())
    }

    /** Appends one encoded entry as field 1 of the backup message. */
    fun writeManga(sink: BufferedSink, encoded: ByteArray) {
        sink.writeVarLong((MANGA_FIELD.toLong() shl 3) or WIRE_LENGTH_DELIMITED.toLong())
        sink.writeVarLong(encoded.size.toLong())
        sink.write(encoded)
    }

    /** Moves past one field's value, copying it to [sink] when there is one. */
    @PublishedApi
    internal fun skipField(source: BufferedSource, wire: Int, sink: BufferedSink?) {
        when (wire) {
            WIRE_VARINT -> source.readVarLong().let { sink?.writeVarLong(it) }
            WIRE_FIXED64 -> copyOrSkip(source, 8, sink)
            WIRE_LENGTH_DELIMITED -> {
                val length = source.readVarLong()
                sink?.writeVarLong(length)
                copyOrSkip(source, length, sink)
            }
            WIRE_FIXED32 -> copyOrSkip(source, 4, sink)
            else -> throw IOException("Unsupported protobuf wire type $wire")
        }
    }

    private fun copyOrSkip(source: BufferedSource, length: Long, sink: BufferedSink?) {
        if (sink == null) source.skip(length) else source.read(sink.buffer, length).also { sink.emit() }
    }

    @PublishedApi
    internal fun BufferedSource.readVarLong(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val byte = readByte().toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("Malformed protobuf varint")
    }

    private fun BufferedSink.writeVarLong(value: Long) {
        var remaining = value
        while (remaining and 0x7FL.inv() != 0L) {
            writeByte(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        writeByte(remaining.toInt())
    }
}
