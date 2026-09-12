package tachiyomi.core.common.util.system

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Covers the narrowing of the bundled decoder's half-float HDR output to 8-bit RGBA.
 *
 * Fixtures are laid out little-endian, as the decoder writes them on every Android ABI, but handed
 * over with the big-endian order a JNI direct buffer surfaces with - the mismatch the reader has to
 * undo.
 */
class HalfFloatTest {

    @Test
    fun `decodes finite halves`() {
        halfToFloat(0x0000) shouldBe 0f
        halfToFloat(0x3C00) shouldBe 1f
        halfToFloat(0x3800) shouldBe 0.5f
        halfToFloat(0x4000) shouldBe 2f
        halfToFloat(0xBC00) shouldBe -1f
        halfToFloat(0x7BFF) shouldBe 65504f
        halfToFloat(0x0001) shouldBe Math.scalb(1f, -24)
    }

    @Test
    fun `decodes infinities and nan`() {
        halfToFloat(0x7C00) shouldBe Float.POSITIVE_INFINITY
        halfToFloat(0xFC00) shouldBe Float.NEGATIVE_INFINITY
        halfToFloat(0x7E00).isNaN() shouldBe true
    }

    @Test
    fun `clips highlights and out of gamut values`() {
        halfToUnorm8(0x3C00) shouldBe 255.toByte()
        halfToUnorm8(0x4000) shouldBe 255.toByte()
        halfToUnorm8(0x7C00) shouldBe 255.toByte()
        halfToUnorm8(0xBC00) shouldBe 0.toByte()
        halfToUnorm8(0x7E00) shouldBe 0.toByte()
        halfToUnorm8(0x3800) shouldBe 128.toByte()
    }

    @Test
    fun `keeps channel order`() {
        val out = halfFloatRgbaToUnorm8(halves(0x3C00, 0x3800, 0x0000, 0x3C00), pixelCount = 1)

        out.array().toList() shouldBe listOf(255, 128, 0, 255).map(Int::toByte)
    }

    @Test
    fun `reads from the start whatever the buffer position`() {
        val src = halves(0x3C00, 0x3C00, 0x3C00, 0x3C00).apply { position(6) }

        halfFloatRgbaToUnorm8(src, pixelCount = 1).array().toList() shouldBe List(4) { 255.toByte() }
    }

    @Test
    fun `rejects a buffer shorter than the pixel count`() {
        shouldThrow<IllegalArgumentException> {
            halfFloatRgbaToUnorm8(halves(0x3C00, 0x3C00, 0x3C00, 0x3C00), pixelCount = 2)
        }
    }

    private fun halves(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer.order(ByteOrder.BIG_ENDIAN)
    }
}
