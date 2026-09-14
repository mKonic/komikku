package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Color
import androidx.annotation.ColorInt
import ca.mpreg.webgpuviewer.renderer.Hdr
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.pow

/**
 * Configuration used by pager viewers.
 */
class WebGpuConfig(
    private val viewer: WebGpuViewer,
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences,
) : ViewerConfig(readerPreferences, scope) {

    var theme = readerPreferences.readerTheme().get()
        private set

    var automaticBackground = false
        private set

    // KMK: what a saved or shared two page spread is laid onto, the same as the standard viewer
    // keeps. The reader theme is the only thing that decides it.
    @ColorInt
    var pageCanvasColor = themeToCanvasColor(theme)
        private set

    var dualPageSplitChangedListener: ((Boolean) -> Unit)? = null

    var imageScaleType = 1
        private set

    var imageZoomType = ReaderPageImageView.ZoomStartPosition.LEFT
        private set

    var imageCropBorders = false
        private set

    var navigateToPan = false
        private set

    var landscapeZoom = false
        private set

    var transitionAnimation = ReaderPreferences.TransitionAnimation.BASIC
        private set

    var transitionAnimationDual = ReaderPreferences.TransitionAnimation.BASIC
        private set

    var cutoutMode = ReaderPreferences.CutoutMode.AVOID
        private set

    var cutoutModeDual = ReaderPreferences.CutoutMode.AVOID
        private set

    var dualPageView = ReaderPreferences.DualPageView.NEVER
        private set

    var continuousMinWidth = 100
        private set

    var zoomOutDisabled = false
        private set

    var continuousGap = 10
        private set

    var upscaling = ReaderPreferences.Upscaling.CATMULL_ROM
        private set

    /** Set by the viewer, so a change reaches the tile cache that has already been built. */
    var upscalingChangedListener: ((ReaderPreferences.Upscaling) -> Unit)? = null

    var colorLut = ""
        private set

    var colorLutIntensity = 100
        private set

    /** Reading the table is file I/O, so the viewer does it rather than this constructor. */
    var colorLutChangedListener: (() -> Unit)? = null

    var grayscale = false
        private set

    var invertedColors = false
        private set

    var toneChangedListener: (() -> Unit)? = null

    var doubleTapZoom = true
        private set

    var pinchZoom = true
        private set

    init {
        readerPreferences.readerTheme()
            .register(
                {
                    theme = it
                    automaticBackground = it == 3
                    pageCanvasColor = themeToCanvasColor(it)
                },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.imageScaleType()
            .register({ imageScaleType = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.zoomStart()
            .register({ zoomTypeFromPreference(it) }, { imagePropertyChangedListener?.invoke() })

        // Border trimming is a per-mode setting in the standard viewers, and a strip is not a
        // paged view: reading the paged one in every mode meant turning trimming on for pages also
        // turned it on for strips, and neither long strip switch did anything under the renderer.
        val cropBorders = when {
            !viewer.isContinuous -> readerPreferences.cropBorders()
            viewer.useGap -> readerPreferences.cropBordersContinuousVertical()
            else -> readerPreferences.cropBordersWebtoon()
        }
        cropBorders
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.upscaler()
            .register({ upscaling = it }, { upscalingChangedListener?.invoke(it) })

        // The gain map is weighted against this while the page is decoded, so a change only shows
        // once the pages are decoded again - which is what imagePropertyChangedListener arranges.
        readerPreferences.hdrPeakStops()
            .register(
                { Hdr.maxPeakValue = 2f.pow(it) },
                { imagePropertyChangedListener?.invoke() },
            )

        // The standard viewers get these from a hardware layer paint on the reader's container.
        // That cannot reach the renderer, which draws into a SurfaceView the view hierarchy only
        // punches a hole for, so they go through the renderer's own filter chain instead.
        readerPreferences.grayscale()
            .register({ grayscale = it }, { toneChangedListener?.invoke() })

        readerPreferences.invertedColors()
            .register({ invertedColors = it }, { toneChangedListener?.invoke() })

        readerPreferences.colorLut()
            .register({ colorLut = it }, { colorLutChangedListener?.invoke() })

        readerPreferences.colorLutIntensity()
            .register({ colorLutIntensity = it }, { colorLutChangedListener?.invoke() })

        readerPreferences.navigateToPan()
            .register({ navigateToPan = it })

        readerPreferences.landscapeZoom()
            .register({ landscapeZoom = it }, { imagePropertyChangedListener?.invoke() })

        // A continuous strip is read like the long strip viewer, not like a paged one, so it takes
        // that viewer's tap zones and inversion. Reading both modes off the paged preferences meant
        // a long strip reader silently got whatever the paged one was configured with.
        val navigationMode =
            if (viewer.isContinuous) readerPreferences.navigationModeWebtoon() else readerPreferences.navigationModePager()
        val navInverted =
            if (viewer.isContinuous) readerPreferences.webtoonNavInverted() else readerPreferences.pagerNavInverted()

        navigationMode
            .register({ this.navigationMode = it }, { updateNavigation(this.navigationMode) })

        navInverted
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        navInverted.changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        // The tap zones are sized when a navigation is built, so a change has to rebuild it. Both
        // standard viewers do this; without it the new zone size only appeared on the next open.
        readerPreferences.smallerTapZone().changes()
            .drop(1)
            .onEach { updateNavigation(this.navigationMode) }
            .launchIn(scope)

        // Splitting a wide page is a per-mode setting in the standard viewers, as border trimming
        // is, so a strip has to read the long strip switches rather than the paged ones.
        val strip = viewer.isContinuous
        val splitPref =
            if (strip) readerPreferences.dualPageSplitWebtoon() else readerPreferences.dualPageSplitPaged()
        val invertPref =
            if (strip) readerPreferences.dualPageInvertWebtoon() else readerPreferences.dualPageInvertPaged()
        val rotatePref =
            if (strip) readerPreferences.dualPageRotateToFitWebtoon() else readerPreferences.dualPageRotateToFit()
        val rotateInvertPref = if (strip) {
            readerPreferences.dualPageRotateToFitInvertWebtoon()
        } else {
            readerPreferences.dualPageRotateToFitInvert()
        }

        splitPref
            .register(
                { dualPageSplit = it },
                {
                    imagePropertyChangedListener?.invoke()
                    dualPageSplitChangedListener?.invoke(it)
                },
            )

        invertPref
            .register({ dualPageInvert = it }, { imagePropertyChangedListener?.invoke() })

        rotatePref
            .register(
                { dualPageRotateToFit = it },
                { imagePropertyChangedListener?.invoke() },
            )

        rotateInvertPref
            .register(
                { dualPageRotateToFitInvert = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.transitionAnimation()
            .register(
                { transitionAnimation = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.transitionAnimationDual()
            .register(
                { transitionAnimationDual = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.cutoutMode()
            .register(
                { cutoutMode = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.cutoutModeDual()
            .register(
                { cutoutModeDual = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.dualPageView()
            .register(
                { dualPageView = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.continuousMinWidth()
            .register(
                { continuousMinWidth = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.webtoonDisableZoomOut()
            .register(
                { zoomOutDisabled = it },
                { imagePropertyChangedListener?.invoke() },
            )

        // Per mode, as in the standard viewers: a strip reads the long strip switches.
        val doubleTapPref = if (strip) {
            readerPreferences.webtoonDoubleTapZoomEnabled()
        } else {
            readerPreferences.pagedDoubleTapZoomEnabled()
        }
        doubleTapPref
            .register({ doubleTapZoom = it }, { imagePropertyChangedListener?.invoke() })

        // Only the long strip viewer offers this, so a paged read keeps pinch zoom either way.
        if (strip) {
            readerPreferences.webtoonPinchToZoomEnabled()
                .register({ pinchZoom = it }, { imagePropertyChangedListener?.invoke() })
        }

        readerPreferences.continuousGap()
            .register(
                { continuousGap = it },
                { imagePropertyChangedListener?.invoke() },
            )
    }

    private fun zoomTypeFromPreference(value: Int) {
        imageZoomType = when (value) {
            // Auto
            1 -> if (viewer.isReversed) {
                ReaderPageImageView.ZoomStartPosition.RIGHT
            } else {
                ReaderPageImageView.ZoomStartPosition.LEFT
            }
            // Left
            2 -> ReaderPageImageView.ZoomStartPosition.LEFT
            // Right
            3 -> ReaderPageImageView.ZoomStartPosition.RIGHT
            // Center
            else -> ReaderPageImageView.ZoomStartPosition.CENTER
        }
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = this.tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return if (viewer.isVertical) {
            LNavigation()
        } else {
            RightAndLeftNavigation()
        }
    }

    override fun updateNavigation(navigationMode: Int) {
        navigator = when (navigationMode) {
            0 -> defaultNavigation()
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            5 -> DisabledNavigation()
            else -> defaultNavigation()
        }
        navigationModeChangedListener?.invoke()
    }

    // KMK: the standard viewer's own mapping, mirrored value for value so a spread saved or shared
    // from either viewer lands on the same background.
    @ColorInt
    private fun themeToCanvasColor(theme: Int): Int = when (theme) {
        1 -> Color.BLACK
        2 -> 0x202125
        else -> Color.WHITE
    }
}
