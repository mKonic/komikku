package tachiyomi.core.common.util.system

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Narrows the bundled decoder's HDR output - four half-floats per pixel, extended sRGB with 1.0 at
 * diffuse white - to 8-bit RGBA. Highlights above white clip; negatives and NaN go to 0.
 *
 * The decoder writes the halves in native byte order, which a buffer's big-endian default would
 * read backwards.
 */
internal fun halfFloatRgbaToUnorm8(src: ByteBuffer, pixelCount: Int): ByteBuffer {
    val halves = src.duplicate().apply { rewind() }.order(ByteOrder.nativeOrder()).asShortBuffer()
    val channels = pixelCount * 4
    require(halves.remaining() >= channels) { "Buffer holds ${halves.remaining()} halves, need $channels" }

    val out = ByteArray(channels)
    for (i in 0 until channels) {
        out[i] = halfToUnorm8(halves.get(i).toInt())
    }
    return ByteBuffer.wrap(out)
}

internal fun halfToUnorm8(half: Int): Byte {
    val value = halfToFloat(half)
    // NaN fails both comparisons and lands on 0.
    val clamped = if (value >= 1f) {
        1f
    } else if (value > 0f) {
        value
    } else {
        0f
    }
    return (clamped * 255f).roundToInt().toByte()
}

internal fun halfToFloat(half: Int): Float {
    val sign = if (half and 0x8000 != 0) -1f else 1f
    val exponent = (half ushr 10) and 0x1F
    val mantissa = half and 0x3FF
    val magnitude = when (exponent) {
        // Subnormal: 2^-10 for the mantissa times the fixed 2^-14 exponent.
        0 -> mantissa / 16_777_216f
        0x1F -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
        else -> Math.scalb(1f + mantissa / 1024f, exponent - 15)
    }
    return sign * magnitude
}
