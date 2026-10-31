package tachiyomi.core.common.util.system

import java.io.BufferedInputStream
import java.io.InputStream

/** Which pages the reader can decode top first, as their bytes arrive. */
object StillSniff {

    /**
     * Whether [input] holds a still JPEG, PNG or WebP, the formats that decode top first. A progressive JPEG
     * counts only with [progressive]: its first pass is the whole page, worth showing while it downloads but
     * only extra work for a file that is all there. [input] is left unread (mark/reset), so it must buffer
     * [STILL_SNIFF_BYTES].
     */
    fun isTopDownStill(input: BufferedInputStream, progressive: Boolean = true): Boolean {
        // readHeader marks and rewinds the stream itself, so the prefix is read after it, under a mark of its own.
        val header = ImageFormat.readHeader(input) ?: return false
        val type = ImageFormat.typeOf(header)
        input.mark(STILL_SNIFF_BYTES)
        try {
            return when (type) {
                ImageUtil.ImageType.JPEG -> progressive || !isProgressiveJpeg(readPrefix(input))
                ImageUtil.ImageType.WEBP -> !ImageFormat.isAnimatedWebp(header)
                ImageUtil.ImageType.PNG -> !isApng(readPrefix(input))
                else -> false
            }
        } catch (_: Exception) {
            return false
        } finally {
            input.reset()
        }
    }

    /** Enough of a file for a PNG's chunks before its first IDAT and a JPEG's segments before its frame. */
    const val STILL_SNIFF_BYTES = 64 * 1024

    private fun readPrefix(input: InputStream): ByteArray {
        val bytes = ByteArray(STILL_SNIFF_BYTES)
        var n = 0
        while (n < bytes.size) {
            val read = input.read(bytes, n, bytes.size - n)
            if (read < 0) break
            n += read
        }
        return bytes.copyOf(n)
    }

    /** Walks a JPEG's segments to its frame header. */
    private fun isProgressiveJpeg(bytes: ByteArray): Boolean {
        var i = 2
        while (i + 4 <= bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return false
            val marker = bytes[i + 1].toInt() and 0xFF
            // SOF2 and its arithmetic-coded twin are progressive; any other SOF is not.
            if (marker == 0xC2 || marker == 0xCA) return true
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return false
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            i += 2 + length
        }
        return false
    }

    private fun isApng(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.ISO_8859_1)
        val actl = text.indexOf("acTL")
        if (actl < 0) return false
        val idat = text.indexOf("IDAT")
        return idat < 0 || actl < idat
    }
}
