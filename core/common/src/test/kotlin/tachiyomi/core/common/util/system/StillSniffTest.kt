package tachiyomi.core.common.util.system

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream

/**
 * [StillSniff.isTopDownStill] decides which pages the reader decodes as they arrive. The fixtures are the segments
 * and chunks it walks, padded past the 32-byte header the format sniff reads.
 */
class StillSniffTest {

    @Test
    fun `a baseline jpeg decodes top first`() {
        check(jpeg(sof = 0xC0), progressive = false) shouldBe true
    }

    @Test
    fun `a progressive jpeg only while downloading`() {
        check(jpeg(sof = 0xC2), progressive = true) shouldBe true
        check(jpeg(sof = 0xC2), progressive = false) shouldBe false
    }

    @Test
    fun `an animated png does not`() {
        check(png(animated = true)) shouldBe false
        check(png(animated = false)) shouldBe true
    }

    @Test
    fun `the stream is left unread`() {
        val bytes = jpeg(sof = 0xC2)
        val input = BufferedInputStream(ByteArrayInputStream(bytes), StillSniff.STILL_SNIFF_BYTES)
        StillSniff.isTopDownStill(input, progressive = false)
        input.readBytes().toList() shouldBe bytes.toList()
    }

    private fun check(bytes: ByteArray, progressive: Boolean = true) =
        StillSniff.isTopDownStill(BufferedInputStream(ByteArrayInputStream(bytes), StillSniff.STILL_SNIFF_BYTES), progressive)

    private fun ints(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // SOI, a JFIF APP0, a quantisation table, then the frame header the walk stops at.
    private fun jpeg(sof: Int) = ints(0xFF, 0xD8) +
        ints(0xFF, 0xE0, 0x00, 0x10) + "JFIF".toByteArray() + ByteArray(10) +
        ints(0xFF, 0xDB, 0x00, 0x43) + ByteArray(0x41) +
        ints(0xFF, sof, 0x00, 0x11) + ByteArray(0x0F) +
        ByteArray(64)

    private fun png(animated: Boolean): ByteArray {
        fun chunk(type: String, length: Int) =
            ints(0, 0, (length shr 8) and 0xFF, length and 0xFF) + type.toByteArray() + ByteArray(length + 4)
        return ints(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", 13) +
            (if (animated) chunk("acTL", 8) else ByteArray(0)) +
            chunk("IDAT", 32)
    }
}
