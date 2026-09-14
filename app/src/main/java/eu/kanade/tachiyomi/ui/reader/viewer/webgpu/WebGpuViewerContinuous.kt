package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlin.math.max

class WebGpuViewerContinuous(activity: ReaderActivity, override val useGap: Boolean = false) :
    WebGpuViewer(activity, isReversed = false, isVertical = true, pager = ImageViewContinuous(activity)) {

    override val isContinuous: Boolean = true

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
