package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.imagedecoder.ImageDecoder
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A still page decoded top first from [input] as its bytes are read, for showing its first rows before the rest
 * are in: the bytes go to the decoder a chunk at a time and each step's new rows are copied into [image].
 *
 * Progressive formats (a progressive JPEG's passes, interlaced PNG) can rewrite rows already there, so what
 * changed since the last [clearDirty] is [dirtyTop] to [rowsDecoded], not only the new rows.
 */
class StreamingRows private constructor(
    val decoder: ImageDecoder,
    private val input: InputStream,
) : Closeable {
    val width = decoder.width
    val height = decoder.height

    /** The whole page, RGBA8, filled from the top. */
    val image: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    var rowsDecoded = 0
        private set

    var isComplete = false
        private set

    /** First row changed since [clearDirty]. */
    var dirtyTop = 0
        private set

    /** Whether rows come from the top down. An HDR page or a gain map needs the whole file first. */
    val isRowOrdered: Boolean get() = !decoder.isHdr && decoder.hdrKind == ImageDecoder.HdrKind.NONE

    private val chunk = ByteArray(CHUNK_BYTES)

    fun clearDirty() {
        dirtyTop = rowsDecoded
    }

    /**
     * Reads on until at least [count] more rows are decoded, or the page is complete. Once a progressive pass
     * has drawn every row, reads the rest without decoding and returns the finished page: each later pass
     * converts the whole page again, which on a phone cost more than the passes were worth.
     */
    fun decodeRows(count: Int) {
        if (!isComplete && rowsDecoded == height) {
            readAll()
            take(decoder.decodeNext())
            return
        }
        val target = minOf(height, rowsDecoded + count)
        while (!isComplete && rowsDecoded < target) {
            val read = input.read(chunk)
            if (read < 0) {
                decoder.markComplete()
                take(decoder.decodeNext())
                continue
            }
            if (read == 0) continue
            decoder.pushData(chunk, 0, read)
            val frame = try {
                decoder.decodeNext()
            } catch (_: ImageDecoder.NeedMoreDataException) {
                continue
            }
            take(frame)
        }
    }

    /** Feeds the rest of the file and marks it complete, for a whole decode of [decoder]. */
    fun readAll() {
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (read > 0) decoder.pushData(chunk, 0, read)
        }
        decoder.markComplete()
    }

    private fun take(frame: ImageDecoder.Frame) {
        try {
            if (!frame.partial) {
                // The finished frame: every row, including any a coarser pass had drawn.
                copyRows(frame, 0, height)
                dirtyTop = 0
                rowsDecoded = height
                isComplete = true
                return
            }
            if (!frame.changed) return
            val top = frame.dirtyY
            val bottom = frame.dirtyY + frame.dirtyHeight
            // Rows only count once everything above them is in: a step below a gap shows nothing yet.
            if (top > rowsDecoded) return
            copyRows(frame, top, bottom)
            dirtyTop = minOf(dirtyTop, top)
            rowsDecoded = maxOf(rowsDecoded, bottom)
        } finally {
            // A partial frame is a view the next decode rewrites; a whole one owns native pixels.
            if (!frame.partial) frame.close()
        }
    }

    private fun copyRows(frame: ImageDecoder.Frame, top: Int, bottom: Int) {
        val rowBytes = width * 4
        val src = frame.image
        src.position(top * rowBytes).limit(bottom * rowBytes)
        val dst = image.duplicate()
        dst.position(top * rowBytes)
        dst.put(src)
    }

    override fun close() = decoder.close()

    companion object {
        /** Bytes handed to the decoder per step: small enough that the first rows come early. */
        private const val CHUNK_BYTES = 64 * 1024

        /** Opens [input] for [StreamingRows], having read only the header. */
        fun open(input: InputStream): StreamingRows {
            // The decoder reads on while available() says more is there, which for a file is all of it.
            val header = object : InputStream() {
                override fun read(): Int = input.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
                override fun available(): Int = 0
            }
            return StreamingRows(ImageDecoder.open(header, complete = false), input)
        }
    }
}
