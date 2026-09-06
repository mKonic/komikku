package tachiyomi.core.common.util.system

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Covers the header parsing that replaced a native call in [ImageUtil.findImageType].
 *
 * Every fixture is a real magic number or ISO-BMFF brand followed by padding - the parser only ever
 * reads the first 32 bytes, so a valid header is enough to exercise it exactly as a whole file
 * would.
 */
class ImageFormatTest {

    @Test
    fun `identifies jpeg`() {
        typeOf(bytes(0xFF, 0xD8, 0xFF, 0xE0)) shouldBe ImageUtil.ImageType.JPEG
    }

    @Test
    fun `identifies png`() {
        typeOf(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) shouldBe ImageUtil.ImageType.PNG
    }

    @Test
    fun `identifies gif in both revisions`() {
        typeOf("GIF87a".toByteArray()) shouldBe ImageUtil.ImageType.GIF
        typeOf("GIF89a".toByteArray()) shouldBe ImageUtil.ImageType.GIF
    }

    @Test
    fun `identifies webp`() {
        typeOf(riff("VP8 ")) shouldBe ImageUtil.ImageType.WEBP
        typeOf(riff("VP8L")) shouldBe ImageUtil.ImageType.WEBP
    }

    @Test
    fun `rejects a riff container that is not webp`() {
        val wav = "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()
        typeOf(wav) shouldBe null
    }

    @Test
    fun `identifies avif by brand`() {
        typeOf(ftyp("avif")) shouldBe ImageUtil.ImageType.AVIF
        typeOf(ftyp("avis")) shouldBe ImageUtil.ImageType.AVIF
    }

    @Test
    fun `identifies heif across its brands`() {
        listOf("heic", "heix", "heim", "heis", "mif1", "hevc", "hevx", "hevm", "hevs", "msf1")
            .forEach { typeOf(ftyp(it)) shouldBe ImageUtil.ImageType.HEIF }
    }

    @Test
    fun `rejects an unrelated isobmff brand`() {
        typeOf(ftyp("isom")) shouldBe null
        typeOf(ftyp("mp42")) shouldBe null
    }

    @Test
    fun `identifies jxl as bare codestream and as container`() {
        typeOf(bytes(0xFF, 0x0A)) shouldBe ImageUtil.ImageType.JXL
        typeOf(bytes(0x00, 0x00, 0x00, 0x0C) + "JXL ".toByteArray() + bytes(0x0D, 0x0A, 0x87, 0x0A)) shouldBe
            ImageUtil.ImageType.JXL
    }

    @Test
    fun `identifies jpeg 2000`() {
        typeOf(bytes(0x00, 0x00, 0x00, 0x0C) + "jP  ".toByteArray() + bytes(0x0D, 0x0A, 0x87, 0x0A)) shouldBe
            ImageUtil.ImageType.JP2
        typeOf(bytes(0xFF, 0x4F, 0xFF, 0x51)) shouldBe ImageUtil.ImageType.JPX
    }

    @Test
    fun `rejects data that is not an image`() {
        typeOf("NOT AN IMAGE AT ALL".toByteArray()) shouldBe null
    }

    @Test
    fun `detects the webp animation flag`() {
        // VP8X chunk, flag byte at offset 20 with the ANIM bit set.
        val animated = riff("VP8X") + bytes(0x0A, 0x00, 0x00, 0x00, 0x02)
        val still = riff("VP8X") + bytes(0x0A, 0x00, 0x00, 0x00, 0x00)

        ImageFormat.isAnimatedWebp(header(animated)) shouldBe true
        ImageFormat.isAnimatedWebp(header(still)) shouldBe false
        // A plain VP8 still has no VP8X chunk to read at all.
        ImageFormat.isAnimatedWebp(header(riff("VP8 "))) shouldBe false
    }

    @Test
    fun `separates heif sequences from single images`() {
        listOf("hevc", "hevx", "hevm", "hevs", "msf1")
            .forEach { ImageFormat.isAnimatedHeif(header(ftyp(it))) shouldBe true }
        listOf("heic", "heix", "heim", "heis", "mif1")
            .forEach { ImageFormat.isAnimatedHeif(header(ftyp(it))) shouldBe false }
    }

    @Test
    fun `reads a full header from a stream that returns one byte at a time`() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40)
        ImageFormat.readHeader(dribbling(png))?.let(ImageFormat::typeOf) shouldBe ImageUtil.ImageType.PNG
    }

    @Test
    fun `leaves a markable stream where it found it`() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40)
        val stream = ByteArrayInputStream(png)

        ImageFormat.readHeader(stream)

        stream.read() shouldBe 0x89
    }

    @Test
    fun `returns null at end of stream`() {
        ImageFormat.readHeader(ByteArrayInputStream(ByteArray(0))) shouldBe null
    }

    @Test
    fun `tolerates a file shorter than the header window`() {
        typeOf(bytes(0xFF, 0xD8, 0xFF)) shouldBe ImageUtil.ImageType.JPEG
        typeOf(bytes(0xFF)) shouldBe null
    }

    private fun typeOf(prefix: ByteArray): ImageUtil.ImageType? =
        ImageFormat.readHeader(ByteArrayInputStream(prefix))?.let(ImageFormat::typeOf)

    private fun header(prefix: ByteArray): ByteArray = ImageFormat.readHeader(ByteArrayInputStream(prefix))!!

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** `RIFF` + a size field + `WEBP` + the four-character code of the first chunk. */
    private fun riff(chunk: String): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + chunk.toByteArray()

    /** A box length, the `ftyp` marker and a major brand - the shape every HEIF/AVIF file opens with. */
    private fun ftyp(brand: String): ByteArray =
        bytes(0x00, 0x00, 0x00, 0x20) + "ftyp".toByteArray() + brand.toByteArray() + ByteArray(20)

    /** Hands back one byte per read, the way a slow network stream does. */
    private fun dribbling(data: ByteArray): InputStream = object : InputStream() {
        private val delegate = ByteArrayInputStream(data)
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (len == 0) 0 else delegate.read(b, off, 1)
        override fun markSupported(): Boolean = false
    }
}
