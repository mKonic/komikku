package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import androidx.annotation.ColorInt
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.ui.util.fastCoerceIn
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.GamepadInput
import kotlinx.coroutines.launch
import kotlin.math.max

class WebGpuViewerContinuous(
    activity: ReaderActivity,
    useGap: Boolean = false,
    // KMK -->
    @ColorInt seedColor: Int? = null,
    // KMK <--
) : WebGpuViewer(
    activity,
    isReversed = false,
    isVertical = true,
    pager = ImageViewContinuous(activity),
    seedColor = seedColor,
    isContinuous = true,
    useGap = useGap,
) {

    // How many pages the viewport shows depends on the zoom, and a page on screen has to be
    // decoded rather than merely reserved - so the window follows what the last frame reached.
    override val preloadAhead get() = max(3, state.pagesBelow)
    override val preloadBehind get() = max(1, state.pagesAbove)

    // The state reaches MAX_VISIBLE_PAGES either side of the current page whatever the zoom - to
    // measure the document's end as well as to draw - and every page in that reach is created on
    // demand here. Sized under it, each frame evicts exactly what the next one asks for.
    override val cacheSize get() = 2 + 2 * ImageViewerContinuousState.MAX_VISIBLE_PAGES

    private val state get() = (pager as ImageViewContinuous).state

    /**
     * The page [ImageViewerContinuousState.onViewport] last reported, so only a change is acted on.
     * Read and written on the render thread alone, which is the only caller.
     */
    private var lastReadThrough: ImagePage? = null

    init {
        // Scrolling clear of a transition page is the only point this mode can call the chapter
        // before it finished - reaching a page's top comes a screen too early.
        //
        // onViewport reports every frame rather than only when the page changes, so the identity
        // check is ours to make: selecting a page writes chapter progress and can start a chapter
        // load, neither of which belongs at frame rate. Comparing against the last report rather
        // than the last selection keeps scrolling back up over a transition and down again
        // selecting that last page again.
        state.onViewport = onViewport@{ readThrough ->
            if (readThrough === lastReadThrough) return@onViewport
            lastReadThrough = readThrough
            val chapter = (readThrough as? TransitionPage)?.prevChapter ?: return@onViewport
            val lastPage = chapter.pages?.lastOrNull() ?: return@onViewport
            activity.onPageSelected(lastPage)
        }
    }

    private fun scrollByHalfPage(direction: Int) {
        state.animateScroll(direction * state.height / 2f)
    }

    // A whole screen, not the forward tap's half of one: that is the distance the standard long
    // strip viewer covers per auto scroll interval, and a shorter step reads as a crawl at the
    // same setting.
    override fun autoScrollStep() {
        state.animateScroll(state.height.toFloat())
    }

    // KMK -->
    /**
     * The strip's zoom about the centre of the screen and its horizontal pan, as the library's own
     * gestures move them: [ImageViewerContinuousState.offsetX] is a fraction of the width, larger
     * showing more of the left, and the scroll is in unscaled pixels.
     */
    override fun gamepadTarget(): GamepadInput.Target = object : GamepadInput.Target {
        override val isStrip get() = true

        override fun isZoomedIn() = state.scale > 1.01f

        override fun zoomStep(zoomIn: Boolean) {
            val from = state.scale
            val to = (from * if (zoomIn) ZOOM_STEP else 1 / ZOOM_STEP).fastCoerceIn(state.minScale, state.maxScale)
            state.animationJob?.cancel()
            // The library's scope: animate needs the frame clock its composition provides.
            state.animationJob = state.scope?.launch {
                state.isScaleAnimating = true
                try {
                    animate(from, to, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                        zoomTo(value)
                    }
                } finally {
                    state.isScaleAnimating = false
                    state.invalidate()
                }
            }
        }

        override fun zoomBy(factor: Float) {
            if (state.isScaleAnimating) return
            zoomTo(state.scale * factor)
        }

        override fun panBy(dx: Float, dy: Float) {
            val maxOffsetX = max(0f, (state.scale - 1f) / (2f * state.scale))
            state.offsetX = (state.offsetX - dx / state.scale).fastCoerceIn(-maxOffsetX, maxOffsetX)
            state.invalidate()
        }

        override fun scrollBy(dy: Float) {
            state.scrollBy(dy * state.height / state.scale)
            state.invalidate()
        }
    }

    /** Sets the scale about the centre of the screen, as the library's pinch does about its focus. */
    private fun zoomTo(target: Float) {
        val scale = target.fastCoerceIn(state.minScale, state.maxScale)
        if (scale == state.scale) return
        val diff = 1f / scale - 1f / state.scale
        val maxOffsetX = max(0f, (scale - 1f) / (2f * scale))
        state.scale = scale
        state.offsetX = state.offsetX.fastCoerceIn(-maxOffsetX, maxOffsetX)
        // The camera hangs from the top of the screen, so holding the centre moves the scroll.
        state.scrollBy(-0.5f * diff * state.height)
        state.invalidate()
    }
    // KMK <--

    override fun moveRight() = scrollByHalfPage(1)

    override fun moveLeft() = scrollByHalfPage(-1)

    override fun moveToPage(page: ReaderPage) {
        super.moveToPage(page)
        // Also for a jump to the page already showing, which turns nothing to slide in.
        state.resetScroll()
    }

    override fun animateTurn(direction: Int, fromSpread: ImagePage) {
        state.animateSlideIn(direction)
    }
}

/** A gamepad zoom step's factor, as the standard viewers'. */
private const val ZOOM_STEP = 1.25f
