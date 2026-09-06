package tachiyomi.core.common.util.system

import java.io.InputStream

/**
 * Identifies image formats from their header bytes.
 *
 * Split out from [ImageUtil] and deliberately free of Android types: this runs for every page,
 * every cover and every file a local source lists, so it has to be cheap, and keeping it pure
 * means it can be unit tested without a device.
 *
 * Replaces a JNI call into the bundled decoder. Comparing a dozen bytes costs a fraction of
 * crossing into native code - let alone of opening a decoder over the whole image to read its
 * type, which is what asking the decoder for a format now requires.
 */
internal object ImageFormat {

    /**
     * Enough for a RIFF header plus its first chunk's flag byte, and for an ISO-BMFF `ftyp` box's
     * major brand - the deepest either sniffed field sits.
     */
    const val HEADER_BYTES = 32

    private val MAGIC_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val MAGIC_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val MAGIC_GIF = "GIF8".toByteArray(Charsets.US_ASCII)
    private val MAGIC_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val MAGIC_WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val MAGIC_VP8X = "VP8X".toByteArray(Charsets.US_ASCII)
    private val MAGIC_FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

    /** A bare JPEG XL codestream; the boxed form is caught by [MAGIC_JXL_BOX] instead. */
    private val MAGIC_JXL_STREAM = byteArrayOf(0xFF.toByte(), 0x0A)
    private val MAGIC_JXL_BOX = "JXL ".toByteArray(Charsets.US_ASCII)

    private val MAGIC_JP2_BOX = "jP  ".toByteArray(Charsets.US_ASCII)
    private val MAGIC_J2K_STREAM = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)

    private val AVIF_BRANDS = setOf("avif", "avis")

    /** Every brand an image-bearing HEIF file declares, sequences included. */
    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis", "mif1",
        "hevc", "hevx", "hevm", "hevs", "msf1",
    )

    /** The subset of [HEIF_BRANDS] that carries more than one frame. */
    private val HEIF_SEQUENCE_BRANDS = setOf("hevc", "hevx", "hevm", "hevs", "msf1")

    /**
     * Reads the leading bytes every container format puts its magic number and brands in, without
     * consuming them from a stream that can rewind. Returns null at end of stream.
     */
    fun readHeader(stream: InputStream): ByteArray? {
        val bytes = ByteArray(HEADER_BYTES)

        val length = if (stream.markSupported()) {
            stream.mark(bytes.size)
            stream.fill(bytes).also { stream.reset() }
        } else {
            stream.fill(bytes)
        }

        return if (length <= 0) null else bytes
    }

    fun typeOf(h: ByteArray): ImageUtil.ImageType? = when {
        h.startsWith(MAGIC_JPEG) -> ImageUtil.ImageType.JPEG
        h.startsWith(MAGIC_PNG) -> ImageUtil.ImageType.PNG
        h.startsWith(MAGIC_GIF) -> ImageUtil.ImageType.GIF
        h.startsWith(MAGIC_RIFF) && h.matchesAt(8, MAGIC_WEBP) -> ImageUtil.ImageType.WEBP
        h.startsWith(MAGIC_JXL_STREAM) || h.matchesAt(4, MAGIC_JXL_BOX) -> ImageUtil.ImageType.JXL
        h.matchesAt(4, MAGIC_FTYP) -> when (brandAt(h, 8)) {
            in AVIF_BRANDS -> ImageUtil.ImageType.AVIF
            in HEIF_BRANDS -> ImageUtil.ImageType.HEIF
            else -> null
        }
        h.matchesAt(4, MAGIC_JP2_BOX) -> ImageUtil.ImageType.JP2
        h.startsWith(MAGIC_J2K_STREAM) -> ImageUtil.ImageType.JPX
        else -> null
    }

    /** The ANIM bit of a RIFF `VP8X` chunk - a still WebP has no such chunk at all. */
    fun isAnimatedWebp(h: ByteArray): Boolean =
        h.matchesAt(12, MAGIC_VP8X) && h.size > 20 && (h[20].toInt() and 0x02) != 0

    /** Sequence brands mark a multi-image HEIF; the single-image brands never animate. */
    fun isAnimatedHeif(h: ByteArray): Boolean = brandAt(h, 8) in HEIF_SEQUENCE_BRANDS

    private fun brandAt(h: ByteArray, offset: Int): String? {
        if (h.size < offset + 4) return null
        return String(h, offset, 4, Charsets.US_ASCII)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matchesAt(0, prefix)

    private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean {
        if (size < offset + expected.size) return false
        for (i in expected.indices) {
            if (this[offset + i] != expected[i]) return false
        }
        return true
    }

    /**
     * Reads until [buffer] is full or the stream ends, returning how many bytes were read.
     *
     * A single [InputStream.read] may return fewer bytes than asked for, and does so in practice on
     * a network-backed stream - which would truncate the header and misidentify the format.
     * Hand-rolled rather than `readNBytes`, which needs API 33 against a minSdk of 26.
     */
    private fun InputStream.fill(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }
}
