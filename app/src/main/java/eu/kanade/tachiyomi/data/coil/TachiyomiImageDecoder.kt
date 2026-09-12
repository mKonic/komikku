package eu.kanade.tachiyomi.data.coil

import android.app.Application
import android.graphics.Bitmap
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import com.hippo.unifile.UniFile
import mihon.core.archive.CbzCrypto
import mihon.core.archive.CbzCrypto.getCoverStream
import mihon.core.archive.archiveReader
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = Injekt.get<Application>()

    override suspend fun decode(): DecodeResult {
        // SY -->
        var coverStream: BufferedInputStream? = null
        if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
            if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                coverStream = UniFile.fromFile(resources.file().toFile())
                    ?.archiveReader(context = context)
                    ?.getCoverStream()
            }
        }
        val decoder = resources.sourceOrNull()?.use {
            coverStream.use { coverStream ->
                ImageDecoder.new(coverStream ?: it.inputStream())
            }
        }
        // SY <--

        // The pixels come back in a Java direct buffer, so the decoder's native copy of the source
        // can go now rather than whenever the finalizer gets to it.
        val decoded = decoder.use {
            check(it != null && it.pages > 0) { "Failed to initialize decoder" }
            it.decode()
        }
        val srcWidth = decoded.width
        val srcHeight = decoded.height
        check(srcWidth > 0 && srcHeight > 0) { "Failed to decode image" }

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )

        // The decoder hands back a full-resolution RGBA buffer, so any downsampling happens after
        // the copy rather than during decode. Only formats the platform decoder cannot read reach
        // this class at all, so that cost is paid on AVIF, JXL and JPEG 2000 - never on a page.
        var bitmap = ImageUtil.toBitmap(decoded)

        if (sampleSize > 1) {
            val scaled = bitmap.scale(
                (srcWidth / sampleSize).coerceAtLeast(1),
                (srcHeight / sampleSize).coerceAtLeast(1),
            )
            bitmap.recycle()
            bitmap = scaled
        }

        if (
            options.bitmapConfig == Bitmap.Config.HARDWARE &&
            ImageUtil.canUseHardwareBitmap(bitmap)
        ) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return when {
                // KMK -->
                options.newDecoder -> RawImageDecoder(result.source)
                // KMK <--
                options.customDecoder || isApplicable(result.source.source()) ->
                    TachiyomiImageDecoder(result.source, options)
                else -> null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().buffered().use { stream ->
                ImageUtil.findImageType(stream)
            }
            // SY -->
            source.peek().inputStream().use { stream ->
                if (CbzCrypto.detectCoverImageArchive(stream)) return true
            }
            // SY <--
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}

// KMK -->
/**
 * Decodes straight into the pixel buffer the WebGPU renderer uploads, skipping the [Bitmap] a
 * normal decode would allocate and then immediately throw away.
 *
 * The result is not drawable: the pixels are meant for the GPU, never for a [Canvas]. Callers ask
 * for this decoder explicitly with [newDecoder] and unwrap [RawImage.result] themselves.
 */
class RawImageDecoder(private val resources: ImageSource) : Decoder {

    class RawImage(val result: ImageDecoder.DecodeResult) : Image {
        override val size: Long get() = result.image.capacity().toLong()
        override val width: Int get() = result.width
        override val height: Int get() = result.height
        override val shareable: Boolean get() = true

        override fun draw(canvas: Canvas) = Unit
    }

    override suspend fun decode(): DecodeResult {
        val decoded = ImageDecoder.new(resources.source().inputStream()).use { it.decode() }
        return DecodeResult(image = RawImage(decoded), isSampled = false)
    }
}
// KMK <--
