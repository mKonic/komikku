package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.core.net.toUri
import androidx.webgpu.GPUTexture
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.ImageUtil
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.closeTo
import ca.mpreg.webgpuviewer.draw.TextAlign
import ca.mpreg.webgpuviewer.filter.FilterLut3d
import ca.mpreg.webgpuviewer.filter.Lut3d
import ca.mpreg.webgpuviewer.renderer.GainmapInput
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.UpscalerArtCnn
import ca.mpreg.webgpuviewer.renderer.UpscalerCatmullRom
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TransitionCube
import ca.mpreg.webgpuviewer.transition.TransitionCubeOuter
import ca.mpreg.webgpuviewer.transition.TransitionFade
import ca.mpreg.webgpuviewer.transition.TransitionFadeWhite
import ca.mpreg.webgpuviewer.transition.TransitionFlip
import ca.mpreg.webgpuviewer.transition.TransitionFlipLeft
import ca.mpreg.webgpuviewer.transition.TransitionFlipRight
import ca.mpreg.webgpuviewer.transition.TransitionNone
import ca.mpreg.webgpuviewer.transition.TransitionSphere
import ca.mpreg.webgpuviewer.transition.TransitionStackDown
import ca.mpreg.webgpuviewer.transition.TransitionStackLeft
import ca.mpreg.webgpuviewer.transition.TransitionStackRight
import ca.mpreg.webgpuviewer.transition.TransitionStackUp
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import com.google.android.material.color.MaterialColors
import de.stefan_oltmann.kim.Kim
import de.stefan_oltmann.kim.android.readMetadata
import de.stefan_oltmann.kim.format.tiff.constant.TiffTag
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TransitionAnimation
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.TreeSet
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

// KMK -->
/**
 * Threads for the decode workers, shared by every viewer so a new one reuses an idle thread. A viewer used to start a
 * thread of its own, and a thread that has touched the GPU can keep a driver connection after it ends (one GPU pipe and
 * about 4 MB per reader open on the emulator). Each live viewer's worker still gets a thread to park in its lock.
 */
private val decodeDispatcher = ThreadPoolExecutor(0, Int.MAX_VALUE, 5L, TimeUnit.MINUTES, SynchronousQueue()) { r ->
    Thread(r, "WebGpuViewer-Decode").apply { isDaemon = true }
}.asCoroutineDispatcher()
// KMK <--

open class WebGpuViewer(
    val activity: ReaderActivity,
    val isReversed: Boolean,
    val isVertical: Boolean,
    val pager: ImageView = ImageView(activity, isVertical = isVertical, isReversed = isReversed),
) : Viewer {

    open val isContinuous: Boolean = false

    /** Whether a continuous strip leaves a gap between pages; false for a paged viewer. */
    open val useGap: Boolean = false

    // KMK --> komikku resolves dependencies through Injekt
    val readerPreferences by lazy { Injekt.get<ReaderPreferences>() }
    // KMK <--

    /** Resolved once: render() asks per frame, and createReaderThemeContext builds a Resources. */
    @Volatile
    private var cachedBackgroundColor: Int? = null

    @Volatile
    private var cachedOnBackgroundColor: Int? = null

    private fun readerBackgroundColor(): Int =
        cachedBackgroundColor ?: activity.baseContext.readerBackgroundColor(config.theme)
            .also { cachedBackgroundColor = it }

    private fun readerOnBackgroundColor(): Int = cachedOnBackgroundColor ?: MaterialColors.getColor(
        activity.createReaderThemeContext(),
        com.google.android.material.R.attr.colorOnBackground,
        Color.WHITE,
    ).also { cachedOnBackgroundColor = it }

    private val scope = MainScope()

    // Guards pageCache, decodeQueue, deferredCleanup and chapterPreloadsInFlight.
    private val lock = Object()

    /** Without it the worker parks in [lock].wait() after [destroy], keeping the activity alive. */
    @Volatile
    private var destroyed = false

    private val pageCache = LinkedHashMap<PageKey, ViewerPage>()

    // Processed LIFO - last added is highest priority.
    private val decodeQueue = ArrayDeque<ViewerReaderPage>()

    /**
     * Indices of the pages that take a spread to themselves, by chapter - see [spreadStartIndex].
     * Outlives [pageCache]: every page after one of these depends on it, long since evicted.
     */
    private val loneIndices = HashMap<Long?, TreeSet<Int>>()

    /** Chapters [preloadChapterThenRetry] is already waiting on, by id. */
    private val chapterPreloadsInFlight = HashSet<Long?>()

    /**
     * Which side of a dual-page spread a [ViewerReaderPage] belongs on - app-level bookkeeping
     * for [getSpreadAnchor]/[buildSpreadPage], independent of the decoded image itself.
     */
    internal enum class SpreadPosition { LEFT, RIGHT, SINGLE }

    /** Above this, an untagged page is a spread already, not half of one. */
    private val wideAspect = 1.2f

    /** How far two untagged pages' aspect ratios may differ and still pair. */
    private val pairAspectTolerance = 0.1f

    private sealed class PageKey {
        data class Reader(val chapterId: Long?, val index: Int, val half: SplitHalf? = null) : PageKey()
        data class Transition(val prevId: Long?, val nextId: Long?) : PageKey()
    }

    private fun pageKey(page: ViewerPage): PageKey = when (page) {
        is ViewerReaderPage -> PageKey.Reader(page.page.chapter.chapter.id, page.page.index, page.half)
        is ViewerTransitionPage -> PageKey.Transition(page.prevChapter?.chapter?.id, page.nextChapter?.chapter?.id)
        else -> PageKey.Transition(null, null)
    }

    private fun findInCache(key: PageKey): ViewerPage? = pageCache[key]

    /** Check if a page is in the cache by identity. O(1) via key lookup. */
    private fun pageInCache(page: ViewerPage): Boolean = pageCache[pageKey(page)] === page

    /**
     * Queue a page for decoding if not already queued/loading/decoded.
     * If prioritize=true and page is already queued, moves it to front.
     * Must be called while holding lock.
     */
    private fun queueForDecode(page: ViewerReaderPage, prioritize: Boolean = false) {
        // Already has a decoded image
        if (page.isDecoded) return

        when (page.state) {
            PageState.IDLE -> {
                page.state = PageState.QUEUED
                if (prioritize) {
                    decodeQueue.addLast(page)
                } else {
                    decodeQueue.addFirst(page)
                }
                lock.notify()
            }

            PageState.QUEUED -> {
                if (prioritize && decodeQueue.remove(page)) {
                    decodeQueue.addLast(page)
                }
            }

            PageState.LOADING, PageState.DECODING -> {}
        }
    }

    init {
        scope.launch(decodeDispatcher) {
            try {
                while (!destroyed) {
                    // Popped, vetted and marked under one acquisition - no window for an eviction.
                    val page = synchronized(lock) {
                        while (decodeQueue.isEmpty()) {
                            if (destroyed) return@launch
                            lock.wait()
                        }
                        val candidate = decodeQueue.removeLast()
                        if (pageInCache(candidate) && !candidate.isDecoded) {
                            candidate.apply { state = PageState.DECODING }
                        } else {
                            if (pageInCache(candidate)) candidate.state = PageState.IDLE
                            null
                        }
                    } ?: continue

                    try {
                        decodeReaderPage(page)
                    } catch (e: CancellationException) {
                        // Caught below, the loop would park in wait() on a dead scope.
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "decodeReaderPage: ${e.message}" }
                        synchronized(lock) {
                            if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                                val oldImagePage = page.imagePage
                                val errorMessage = e.message ?: "Failed to decode image"
                                page.imagePage = ErrorPage(errorMessage, page.spreadPosition, page)
                                // KMK -->
                                page.page.markDisplayFailed()
                                // KMK <--
                                page.state = PageState.IDLE
                                cleanupImage(oldImagePage)
                                page.imagePage.invalidate()
                            } else {
                                if (pageInCache(page)) page.state = PageState.IDLE
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // A stray interrupt; destroy() wakes the worker with notifyAll instead.
            } catch (_: CancellationException) {
                // Scope cancelled with the viewer.
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Decode worker died" }
            }
        }
    }

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = WebGpuConfig(this, scope, readerPreferences)

    // Read from the render and decode threads, via the prevChapter/nextChapter getters.
    @Volatile
    var viewerChapters: ViewerChapters? = null

    val pages: List<ReaderPage>? get() = (currentPage as? ViewerReaderPage)?.page?.chapter?.pages

    @Volatile
    var currentPage: ViewerPage? = null

    /**
     * What a running page turn animates away from, kept out of [evictFarthestPage]'s reach - a
     * jump preloads enough pages to evict it. Replaced by the next turn's rather than cleared.
     */
    @Volatile
    private var pinnedFromPage: ImagePage? = null

    /** True while [pinnedFromPage] is drawing [image], as itself or as a spread side. */
    private fun isPinnedImage(image: ImagePage): Boolean {
        val pinned = pinnedFromPage ?: return false
        if (pinned === image) return true
        return pinned is ImagePage.ImageSpread && (pinned.left === image || pinned.right === image)
    }

    /** True while [pinnedFromPage] is drawing [page]'s image, as itself or as a spread side. */
    private fun isPinned(page: ViewerPage): Boolean = isPinnedImage(page.imagePage)

    /** Images swapped out while [pinnedFromPage] was still drawing them, i.e. mid page turn. */
    private val deferredCleanup = mutableListOf<ImagePage>()

    /** Cleans up [image], or defers it while [pinnedFromPage] draws it. Call under [lock]. */
    private fun cleanupImage(image: ImagePage) {
        if (isPinnedImage(image)) deferredCleanup.add(image) else image.cleanup()
    }

    /** Releases what [pinnedFromPage] no longer protects. Call under [lock]. */
    private fun flushDeferredCleanup() {
        val iterator = deferredCleanup.iterator()
        while (iterator.hasNext()) {
            val image = iterator.next()
            if (!isPinnedImage(image)) {
                image.cleanup()
                iterator.remove()
            }
        }
    }

    open val preloadAhead = 3
    open val preloadBehind = 2

    /**
     * Everything [preloadPages] reaches, plus slack. Sized exactly, a chapter transition page - or
     * in dual mode a spread partner - evicts a page the next fetch asks for, and it decodes again.
     */
    open val cacheSize get() = 1 + preloadAhead + preloadBehind + if (isDualPageMode()) 3 else 1

    enum class PageState {
        IDLE,
        QUEUED,
        LOADING,
        DECODING,
    }

    /**
     * Evicts the page farthest from reference. Must be called while holding lock.
     *
     * Never evicts [reference], [currentPage] or what [pinnedFromPage] draws. Returns false when
     * nothing was evictable, so a trim loop stops instead of spinning.
     *
     * @param reference The page to use as reference (defaults to currentPage)
     */
    private fun evictFarthestPage(reference: ViewerPage? = null): Boolean {
        val current = reference ?: currentPage ?: return false
        val candidates =
            pageCache.values.filter { it !== current && it !== currentPage && !isPinned(it) }.toMutableSet()
        if (candidates.isEmpty()) return false

        // Read once - the getter measures the viewport.
        val reach = cacheSize

        fun findNext(page: ViewerPage): ViewerPage? = when (page) {
            is ViewerReaderPage -> {
                val chapterId = page.page.chapter.chapter.id
                val nextIndex = page.page.index + 1
                candidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == chapterId && it.page.index == nextIndex
                } ?: candidates.find { it is ViewerTransitionPage && it.prevChapter?.chapter?.id == chapterId }
                    ?: page.nextChapter?.chapter?.id?.let { nextChapterId ->
                        candidates.find {
                            it is ViewerReaderPage && it.page.chapter.chapter.id == nextChapterId && it.page.index == 0
                        }
                    }
            }

            is ViewerTransitionPage -> {
                val nextChapterId = page.nextChapter?.chapter?.id
                candidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == nextChapterId && it.page.index == 0
                }
            }

            else -> null
        }

        fun findPrev(page: ViewerPage): ViewerPage? = when (page) {
            is ViewerReaderPage -> {
                val chapterId = page.page.chapter.chapter.id
                val prevIndex = page.page.index - 1
                candidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == chapterId && it.page.index == prevIndex
                } ?: candidates.find { it is ViewerTransitionPage && it.nextChapter?.chapter?.id == chapterId }
                    ?: page.prevChapter?.let { prevChapter ->
                        prevChapter.pages?.lastIndex?.let { lastIndex ->
                            candidates.find {
                                it is ViewerReaderPage && it.page.chapter.chapter.id == prevChapter.chapter.id &&
                                    it.page.index == lastIndex
                            }
                        }
                    }
            }

            is ViewerTransitionPage -> {
                val prevChapterId = page.prevChapter?.chapter?.id
                page.prevChapter?.pages?.lastIndex?.let { lastIndex ->
                    candidates.find {
                        it is ViewerReaderPage && it.page.chapter.chapter.id == prevChapterId &&
                            it.page.index == lastIndex
                    }
                }
            }

            else -> null
        }

        var farthest: ViewerPage? = null
        var forward: ViewerPage? = current
        var backward: ViewerPage? = current

        for (i in 0 until reach) {
            if (candidates.isEmpty()) break
            forward = forward?.let { findNext(it) }
            backward = backward?.let { findPrev(it) }
            if (forward == null && backward == null) break
            if (forward != null && candidates.remove(forward)) farthest = forward
            if (backward != null && candidates.remove(backward)) farthest = backward
        }

        val toRemove = candidates.firstOrNull() ?: farthest ?: return false

        pageCache.remove(pageKey(toRemove))
        decodeQueue.remove(toRemove)
        toRemove.state = PageState.IDLE
        // Through the pin: a decode that swapped this page's image leaves its spread unguarded.
        (toRemove as? ViewerReaderPage)?.spreadPage?.let(::cleanupImage)
        cleanupImage(toRemove.imagePage)
        return true
    }

    /**
     * Gets or creates a page. Thread-safe.
     * @param referencePage The page to use as reference for eviction (defaults to currentPage)
     */
    fun getPage(
        page: ReaderPage,
        referencePage: ViewerPage? = null,
        // KMK: which half of a page split in two, or null when it is shown whole.
        half: SplitHalf? = if (config.dualPageSplit) SplitHalf.FIRST else null,
    ): ViewerPage {
        val key = PageKey.Reader(page.chapter.chapter.id, page.index, half)
        return synchronized(lock) {
            findInCache(key) ?: ViewerReaderPage(page, half).also { newPage ->
                pageCache[key] = newPage
                val limit = cacheSize
                while (pageCache.size > limit) {
                    if (!evictFarthestPage(referencePage ?: newPage)) break
                }
            }
        }
    }

    fun getPage(
        prevChapter: ReaderChapter?,
        nextChapter: ReaderChapter?,
        referencePage: ViewerPage? = null,
    ): ViewerPage {
        val key = PageKey.Transition(prevChapter?.chapter?.id, nextChapter?.chapter?.id)
        return synchronized(lock) {
            findInCache(key) ?: ViewerTransitionPage(prevChapter, nextChapter).also { newPage ->
                pageCache[key] = newPage
                val limit = cacheSize
                while (pageCache.size > limit) {
                    if (!evictFarthestPage(referencePage ?: newPage)) break
                }
            }
        }
    }

    /**
     * Kicks off loading [chapter] and, once its pages actually show up, re-runs
     * [preloadPages] from the current page - [ReaderActivity]'s viewModel.preload isn't
     * guaranteed to have finished loading by the time it returns, so a single immediate
     * retry can race it and silently never queue the adjacent chapter's edge page for
     * decode. Gives up after 5 seconds if the chapter never finishes loading.
     */
    private fun preloadChapterThenRetry(chapter: ReaderChapter) {
        // fetchPage reaches prev/next per frame - unguarded, each frame starts another 5s poll.
        val chapterId = chapter.chapter.id
        synchronized(lock) {
            if (!chapterPreloadsInFlight.add(chapterId)) return
        }

        scope.launch(Dispatchers.Default) {
            try {
                activity.viewModel.preload(chapter)
                repeat(25) {
                    if (chapter.state is ReaderChapter.State.Loaded) {
                        currentPage?.let { preloadPages(it) }
                        return@launch
                    }
                    delay(200.milliseconds)
                }
            } finally {
                synchronized(lock) { chapterPreloadsInFlight.remove(chapterId) }
            }
        }
    }

    /**
     * Puts a page that failed back through the loader. The error page is swapped for a progress
     * one first: [decodeReaderPage] sees a page that is not ready and calls [startPageLoad], which
     * watches the status flow again, so this is all it takes to restart the whole sequence.
     */
    private fun retryPage(page: ViewerReaderPage) {
        page.page.chapter.pageLoader?.retryPage(page.page)
        synchronized(lock) {
            if (!pageInCache(page)) return
            val old = page.imagePage
            page.imagePage = ProgressPage()
            page.state = PageState.IDLE
            cleanupImage(old)
            page.imagePage.invalidate()
            queueForDecode(page, prioritize = true)
        }
    }

    inner class ErrorPage internal constructor(
        message: String,
        private val spreadPosition: SpreadPosition = SpreadPosition.SINGLE,
        /** The page that failed, so the button below can put it back through the loader. */
        private val failed: ViewerReaderPage? = null,
    ) : ImagePage.Render(0, 0) {
        override val width: Int
            get() = viewportPageWidth(spreadPosition != SpreadPosition.SINGLE)
        override val height: Int
            get() = pager.state.height

        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        var message: String = message
            set(value) {
                field = value
                invalidate()
            }

        override val backgroundColor: Int = readerBackgroundColor()

        /**
         * Where the retry button landed last time it was drawn, as a fraction of the surface -
         * which is what [ImageViewerState.onTap] reports, not pixels. Written on the render thread
         * and read on the main thread, hence volatile; null until it has been drawn once, and also
         * whenever there is nothing to retry.
         */
        @Volatile
        private var retryBounds: RectF? = null

        /** True when the tap was on the button, so the caller knows not to turn the page as well. */
        fun retryIfHit(x: Float, y: Float): Boolean {
            val bounds = retryBounds ?: return false
            if (!bounds.contains(x, y)) return false
            val page = failed ?: return false
            retryPage(page)
            return true
        }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            val padding = with(pager.state.density) { 24.dp.toPx() }
            val size = scale * with(pager.state.density) { 16.dp.toPx() }

            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)

            val foreground = readerOnBackgroundColor()
            text(
                dst,
                activity.baseContext,
                FontFamily.Default,
                message,
                cx,
                cy,
                size,
                color = foreground,
                align = TextAlign.Center,
                maxWidth = dst.width - 2f * padding,
            )

            if (failed == null) {
                retryBounds = null
                return
            }

            // A drawn button rather than a whole-page tap target: tapping elsewhere still turns
            // the page, which is how a reader gets past a page that will not load at all.
            val label = activity.stringResource(MR.strings.action_retry)
            val buttonHeight = scale * with(pager.state.density) { 44.dp.toPx() }
            val buttonWidth = scale * with(pager.state.density) { 140.dp.toPx() }
            val top = cy + size * 2f
            val left = cx - buttonWidth / 2f

            rect(
                left / dst.width,
                top / dst.height,
                (left + buttonWidth) / dst.width,
                (top + buttonHeight) / dst.height,
                foreground and 0x33FFFFFF.toInt(),
            )
            text(
                dst,
                activity.baseContext,
                FontFamily.Default,
                label,
                cx,
                top + (buttonHeight - size) / 2f,
                size,
                color = foreground,
                align = TextAlign.Center,
                maxWidth = buttonWidth,
            )
            retryBounds = RectF(
                left / dst.width,
                top / dst.height,
                (left + buttonWidth) / dst.width,
                (top + buttonHeight) / dst.height,
            )
        }
    }

    inner class ProgressPage(foregroundColor: Int = readerOnBackgroundColor()) : ImagePage.Render(0, 0) {
        override val width: Int
            get() = viewportPageWidth(isDualPageMode())
        override val height: Int
            get() = pager.state.height

        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        var progress: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        var foregroundColor: Int = foregroundColor
            set(value) {
                field = value
                invalidate()
            }

        override val backgroundColor: Int = readerBackgroundColor()

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            // Its own footprint, so the page carries its background wherever a transition puts it.
            fillPage(dst, x, y, scale, backgroundColor)

            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)

            // Off this page's own width, not dst's: a spread half would otherwise draw a ring
            // sized for the whole screen, straight over its partner.
            val full = width * 0.5f * scale

            circle(cx, cy, full / 2f, 0xAAAAAAAA.toInt())

            val diameter = full * progress.fastCoerceIn(0f, 1f)
            if (diameter > 0) {
                circle(cx, cy, diameter / 2f, foregroundColor)
            }
        }
    }

    inner class TransitionPage(val prevChapter: ReaderChapter?, val nextChapter: ReaderChapter?) :
        ImagePage.Render(0, 0) {
        /** Square, and never a spread side - [buildSpreadPage] hands it back whole. */
        override val width: Int
            get() = min(pager.state.width, pager.state.height)
        override val height: Int
            get() = width

        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        override val backgroundColor: Int = readerBackgroundColor()

        /**
         * The same wording the standard viewers use, rather than the hardcoded English this page
         * carried before: the chapter labels, the "no next chapter" notice at the end of an entry,
         * and the warning that the source skips a run of chapters.
         */
        private fun transitionText(): String {
            val lines: MutableList<String> = mutableListOf()

            if (prevChapter == null && nextChapter != null) {
                lines.add(activity.stringResource(MR.strings.transition_no_previous))
            }
            prevChapter?.chapter?.let { chapter ->
                val label = if (nextChapter == null) MR.strings.transition_current else MR.strings.transition_finished
                lines.add(activity.stringResource(label) + " " + chapter.name)
            }

            val gap = calculateChapterGap(nextChapter, prevChapter)
            if (gap > 0) {
                lines.add(activity.pluralStringResource(MR.plurals.missing_chapters_warning, gap, gap))
            }

            if (nextChapter == null && prevChapter != null) {
                lines.add(activity.stringResource(MR.strings.transition_no_next))
            }
            nextChapter?.chapter?.let { chapter ->
                lines.add(activity.stringResource(MR.strings.transition_next) + " " + chapter.name)
            }

            return lines.joinToString("\n")
        }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            // Its own footprint, so the page carries its background wherever a transition puts it.
            fillPage(dst, x, y, scale, backgroundColor)

            val text = transitionText()

            val padding = with(pager.state.density) { 24.dp.toPx() }
            val size = scale * with(pager.state.density) { 16.dp.toPx() }

            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)

            text(
                dst,
                activity.baseContext,
                FontFamily.Default,
                text,
                cx,
                cy,
                size,
                readerOnBackgroundColor(),
                align = TextAlign.Center,
                maxWidth = dst.width - 2f * padding,
            )
        }
    }

    abstract class ViewerPage {
        abstract val prevChapter: ReaderChapter?
        abstract val nextChapter: ReaderChapter?
        abstract val prev: ViewerPage?
        abstract val next: ViewerPage?

        @Volatile
        var state: PageState = PageState.IDLE

        @Volatile
        open var imagePage: ImagePage = ImagePage.Dummy(400, 400)

        open val isDecoded = true
    }

    inner class ViewerTransitionPage(
        override val prevChapter: ReaderChapter?,
        override val nextChapter: ReaderChapter?,
    ) : ViewerPage() {
        override var imagePage: ImagePage = TransitionPage(prevChapter, nextChapter)

        override val prev: ViewerPage?
            get() = prevChapter?.pages?.lastOrNull()?.let { getPage(it, currentPage) }

        override val next: ViewerPage?
            get() = nextChapter?.pages?.firstOrNull()?.let { getPage(it, currentPage) }
    }

    /**
     * Which half of a page too wide for the screen this is, when the reader splits such pages into
     * two. [FIRST] is the half shown first, which is the right-hand side of a right-to-left read.
     */
    enum class SplitHalf { FIRST, SECOND }

    /**
     * Whether this page is one the reader shows as two. Needs the image, so it answers no until
     * the decode lands - the page graph is read live, so the second half appears by itself once it
     * does. Not while dual page view is on: that mode is already putting two pages on one screen.
     */
    private fun splitsInTwo(page: ViewerReaderPage): Boolean {
        if (!config.dualPageSplit || isDualPageMode()) return false
        return (page.rawAspectRatio ?: 0f) > 1f
    }

    /**
     * Which half of [page] to land on when arriving from the page after it: its second, when it is
     * one the reader splits. A page whose decode has not landed yet answers FIRST and corrects
     * itself on the next read of the graph.
     */
    private fun backHalf(page: ReaderPage): SplitHalf? {
        if (!config.dualPageSplit) return null
        val first = synchronized(lock) {
            findInCache(PageKey.Reader(page.chapter.chapter.id, page.index, SplitHalf.FIRST))
        } as? ViewerReaderPage
        return if (first != null && splitsInTwo(first)) SplitHalf.SECOND else SplitHalf.FIRST
    }

    inner class ViewerReaderPage(val page: ReaderPage, val half: SplitHalf? = null) : ViewerPage() {
        /** Cached spread ImagePage when this page is the anchor of a dual-page spread */
        var spreadPage: ImagePage.ImageSpread? = null

        /** The side the file names, or null for none. Never a value merely derived from the index. */
        @Volatile
        internal var taggedSpreadPosition: SpreadPosition? = null

        /**
         * The whole decoded image's shape, before any trim - so it still reads wide once this page
         * has been cropped to half of a wide one, which is what keeps [splitsInTwo] stable.
         */
        internal val rawAspectRatio: Float?
            get() = (imagePage as? ImagePage.ImageSingle)?.let {
                if (it.isDecoded && it.height > 0) it.width.toFloat() / it.height else null
            }

        /** The decoded image's shape, or null while this page is still a placeholder. */
        internal val aspectRatio: Float?
            get() = (imagePage as? ImagePage.ImageSingle)?.let {
                val height = it.trimHeight
                if (it.isDecoded && height > 0) it.trimWidth.toFloat() / height else null
            }

        /**
         * Which half of a spread this page is on - derived until the file tags it. Without that a
         * still-loading page stays SINGLE, never pairs, and its ring draws mid-screen; deriving it
         * live also re-decides it on a rotation in or out of dual mode.
         *
         * Untagged goes by [wideAspect] first, then [derivedSpreadPosition].
         */
        internal val spreadPosition: SpreadPosition
            get() {
                taggedSpreadPosition?.let { return it }
                if (standsAlone) return SpreadPosition.SINGLE
                return derivedSpreadPosition(page)
            }

        /** True when nothing may share this page's spread - it is one already. */
        internal val standsAlone: Boolean
            get() = taggedSpreadPosition == SpreadPosition.SINGLE || (aspectRatio ?: 0f) > wideAspect

        override var imagePage: ImagePage = ProgressPage()

        override val isDecoded
            get() = (imagePage as? ImagePage.ImageSingle)?.isDecoded == true

        override val prevChapter: ReaderChapter?
            get() = when (page.chapter) {
                viewerChapters?.currChapter -> viewerChapters?.prevChapter
                viewerChapters?.nextChapter -> viewerChapters?.currChapter
                else -> null
            }

        override val nextChapter: ReaderChapter?
            get() = when (page.chapter) {
                viewerChapters?.currChapter -> viewerChapters?.nextChapter
                viewerChapters?.prevChapter -> viewerChapters?.currChapter
                else -> null
            }

        override val prev: ViewerPage?
            get() = page.chapter.pages?.let { pages ->
                // KMK: the two halves of a split page sit between the pages either side of it.
                if (half == SplitHalf.SECOND) {
                    return@let getPage(page, currentPage, SplitHalf.FIRST)
                }
                pages.getOrNull(page.index - 1)
                    ?.let { getPage(it, currentPage, backHalf(it)) } ?: run {
                    val prevChapter = prevChapter ?: return@run getPage(null, page.chapter, currentPage)

                    if (prevChapter.state !is ReaderChapter.State.Loaded) {
                        preloadChapterThenRetry(prevChapter)
                    }

                    if (config.alwaysShowChapterTransition) {
                        getPage(prevChapter, page.chapter, currentPage)
                    } else {
                        prevChapter.pages?.lastOrNull()?.let { getPage(it, currentPage) }
                    }
                }
            }

        override val next: ViewerPage?
            get() = page.chapter.pages?.let { pages ->
                // KMK: a wide page is read as two, so its own second half comes before the page
                // after it. Only once it is decoded - until then nothing knows it is wide.
                if (half == SplitHalf.FIRST && splitsInTwo(this)) {
                    return@let getPage(page, currentPage, SplitHalf.SECOND)
                }
                pages.getOrNull(page.index + 1)?.let { getPage(it, currentPage) } ?: run {
                    val nextChapter = nextChapter ?: return@run getPage(page.chapter, null, currentPage)

                    if (nextChapter.state !is ReaderChapter.State.Loaded) {
                        preloadChapterThenRetry(nextChapter)
                    }

                    if (config.alwaysShowChapterTransition) {
                        getPage(page.chapter, nextChapter, currentPage)
                    } else {
                        nextChapter.pages?.firstOrNull()?.let { getPage(it, currentPage) }
                    }
                }
            }
    }

    /** Read live: these pages are built before the surface has a size, and outlive a rotation. */
    private fun viewportPageWidth(half: Boolean): Int = if (half) pager.state.width / 2 else pager.state.width

    /**
     * Check if dual page mode is currently active based on config and view dimensions.
     * Dual page is never active for continuous (scrolling) viewers.
     */
    fun isDualPageMode(): Boolean {
        if (isContinuous) return false
        return when (config.dualPageView) {
            ReaderPreferences.DualPageView.NEVER -> false
            ReaderPreferences.DualPageView.ALWAYS -> true
            ReaderPreferences.DualPageView.WIDE -> {
                val width = pager.state.width
                val height = pager.state.height
                width > 0 && height > 0 && width.toFloat() / height > 1f
            }
        }
    }

    /** The half a spread opens on: right reading right-to-left, left otherwise. */
    private val anchorPosition get() = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT

    private val partnerPosition get() = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT

    /**
     * Which half a page falls on when nothing tags the file: alternating from its spread's start,
     * anchor then partner. SINGLE outside dual page mode, so nothing pairs while one page fills
     * the viewer.
     */
    private fun derivedSpreadPosition(page: ReaderPage): SpreadPosition {
        if (!isDualPageMode()) return SpreadPosition.SINGLE
        val offset = page.index - spreadStartIndex(page.chapter.chapter.id, page.index)
        return if (offset >= 0 && offset % 2 == 0) anchorPosition else partnerPosition
    }

    /**
     * Where the spread holding [index] starts: just past the last page before it that took one to
     * itself, so the page after a detected spread opens the next one instead of inheriting a parity
     * that page broke. Defaults to 1 - page 0 is the cover, and pairs with nothing.
     */
    private fun spreadStartIndex(chapterId: Long?, index: Int): Int {
        val lone = synchronized(lock) { loneIndices[chapterId]?.lower(index) } ?: return 1
        return lone + 1
    }

    /** Registers whether [page] stands alone, for [spreadStartIndex]. Must hold [lock]. */
    private fun noteIfLone(page: ViewerReaderPage) {
        val indices = loneIndices.getOrPut(page.page.chapter.chapter.id) { TreeSet() }
        if (page.standsAlone) indices.add(page.page.index) else indices.remove(page.page.index)
    }

    /**
     * Whether these two may share a spread, beyond their positions agreeing. Both tagged is taken
     * as read; a pair resting on page order needs the same shape - halves of one sheet scan alike.
     * Undecoded pairs anyway, or a loading page draws its ring mid-screen.
     */
    private fun canPairShapes(anchor: ViewerReaderPage, partner: ViewerReaderPage): Boolean {
        if (anchor.taggedSpreadPosition != null && partner.taggedSpreadPosition != null) return true
        val a = anchor.aspectRatio ?: return true
        val b = partner.aspectRatio ?: return true
        return abs(a - b) <= pairAspectTolerance
    }

    /**
     * Get the anchor page for a spread.
     * RTL: anchor is RIGHT, for LEFT page returns previous RIGHT
     * LTR: anchor is LEFT, for RIGHT page returns previous LEFT
     */
    private fun getSpreadAnchor(page: ViewerPage): ViewerPage {
        if (!isDualPageMode()) return page
        if (page !is ViewerReaderPage) return page

        if (page.spreadPosition == partnerPosition) {
            val prev = page.prev as? ViewerReaderPage ?: return page
            if (prev.page.chapter == page.page.chapter && prev.spreadPosition == anchorPosition &&
                canPairShapes(prev, page)
            ) {
                return prev
            }
        }

        return page
    }

    /** Who [page] pairs with, or null. One verdict for [buildSpreadPage] and [progressPage]. */
    private fun spreadPartner(page: ViewerReaderPage): ViewerReaderPage? {
        if (!isDualPageMode()) return null
        if (page.spreadPosition != anchorPosition) return null
        val next = (page.next as? ViewerReaderPage)?.takeIf { it.page.chapter == page.page.chapter } ?: return null
        return next.takeIf { it.spreadPosition == partnerPosition && canPairShapes(page, it) }
    }

    /** Page to report progress for - the spread's lastmost page, not the anchor. */
    private fun progressPage(page: ViewerPage): ViewerReaderPage? {
        val readerPage = page as? ViewerReaderPage ?: return null
        return spreadPartner(readerPage) ?: readerPage
    }

    private fun buildSpreadPage(page: ViewerPage): ImagePage {
        if (page !is ViewerReaderPage) {
            return page.imagePage
        }

        if (!isDualPageMode()) {
            return page.imagePage
        }

        // Whatever the page is holding takes its half of the seam, decoded or not:
        // [ImagePage.ImageSpread] draws a [ImagePage.Render] side into its own half. A page left
        // out would take the whole viewport instead, hiding its partner with it.
        val imagePage = page.imagePage

        if (page.spreadPosition == SpreadPosition.SINGLE) {
            page.spreadPage = null
            return imagePage
        }

        // Null for a partner reaching here directly, which means no anchor before it - a lone
        // RIGHT at a chapter boundary - so it draws alone on its own side.
        val partnerImagePage = spreadPartner(page)?.imagePage

        // LEFT/RIGHT map directly to the spread's left/right slot - independent of reading
        // direction, which only decides which side is the anchor for pairing purposes above.
        val left = if (page.spreadPosition == SpreadPosition.LEFT) imagePage else partnerImagePage
        val right = if (page.spreadPosition == SpreadPosition.RIGHT) imagePage else partnerImagePage

        // Reuse existing spread if the sides match - preserves transform state
        val existing = page.spreadPage
        if (existing != null && existing.left === left && existing.right === right) {
            return existing
        }

        // Create new spread. Composes the existing page(s) directly, so either side (or both)
        // keeps animating independently via its own already-running frame loop - no copying of
        // animation state needed. The other slot is simply null when there's no partner (yet).
        val spread = ImagePage.ImageSpread(left, right)
        page.spreadPage = spread
        return spread
    }

    // KMK -->
    /**
     * ArtCNN reports for itself whether a device can build its pipelines, and [TileRenderer] falls
     * back to Catmull-Rom when it cannot, so this never has to check anything first.
     */
    private fun applyUpscaler(upscaling: ReaderPreferences.Upscaling) {
        pager.state.upscaler = when (upscaling) {
            ReaderPreferences.Upscaling.CATMULL_ROM -> UpscalerCatmullRom()
            ReaderPreferences.Upscaling.ARTCNN -> UpscalerArtCnn()
        }
    }

    /** The filter is kept across intensity changes, so only picking a new table re-reads a file. */
    private var colorLutFilter: FilterLut3d? = null

    /** The table the filter above holds, or is being read for. */
    private var colorLutSource = ""

    private var colorLutJob: Job? = null

    /**
     * The table is a document the user picked, which can be gone, unreadable or not a `.cube` at
     * all by the time we open it, so a failure drops the filter rather than taking the reader down.
     */
    private fun applyColorLut(uri: String, intensity: Int) {
        // Both the table and its strength come through here, and the config publishes each on its
        // own: without this the strength arriving would start a second read of the same file.
        if (uri == colorLutSource) {
            colorLutFilter?.intensity = intensity / 100f
            return
        }

        colorLutSource = uri
        colorLutJob?.cancel()
        colorLutFilter = null
        updateFilters()
        if (uri.isEmpty()) return

        colorLutJob = scope.launch {
            val lut = withContext(Dispatchers.IO) {
                try {
                    activity.contentResolver.openInputStream(uri.toUri())?.use { stream ->
                        stream.bufferedReader().useLines(Lut3d::parseCube)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Could not read the colour table at $uri" }
                    null
                }
            } ?: return@launch
            colorLutFilter = FilterLut3d(lut).also { it.intensity = config.colorLutIntensity / 100f }
            updateFilters()
        }
    }

    /**
     * Greyscale and inverted colours as the renderer's own filter, because the standard viewers'
     * route - a hardware layer paint on the reader's container - cannot reach a SurfaceView.
     *
     * Both are affine in r, g and b, so a two-entry table reproduces them exactly under the
     * trilinear sampling the filter already does: no larger cube would be more accurate.
     */
    private var toneFilter: FilterLut3d? = null

    private fun applyTone(grayscale: Boolean, invertedColors: Boolean) {
        toneFilter = if (!grayscale && !invertedColors) {
            null
        } else {
            FilterLut3d(toneLut(grayscale, invertedColors))
        }
        updateFilters()
    }

    private fun toneLut(grayscale: Boolean, invertedColors: Boolean): Lut3d {
        val data = FloatArray(2 * 2 * 2 * 3)
        var i = 0
        // Red varies fastest, as in a .cube - see Lut3d.identity.
        for (b in 0..1) {
            for (g in 0..1) {
                for (r in 0..1) {
                    var red = r.toFloat()
                    var green = g.toFloat()
                    var blue = b.toFloat()
                    if (grayscale) {
                        // The weights ColorMatrix.setSaturation(0) uses, so both readers agree.
                        val luma = 0.213f * red + 0.715f * green + 0.072f * blue
                        red = luma
                        green = luma
                        blue = luma
                    }
                    if (invertedColors) {
                        red = 1f - red
                        green = 1f - green
                        blue = 1f - blue
                    }
                    data[i++] = red
                    data[i++] = green
                    data[i++] = blue
                }
            }
        }
        return Lut3d(2, data)
    }

    /**
     * The chain, in the order it runs: the reader's own table first, then the tone change, which
     * matches the standard viewers where the layer paint applies over everything.
     */
    private fun updateFilters() {
        pager.state.filters.filters = listOfNotNull(colorLutFilter, toneFilter)
    }
    // KMK <--

    init {
        pager.state.apply {
            fetchPage = fetch@{ index ->
                val current = currentPage ?: return@fetch null

                // For index 0, return the current spread
                if (index == 0) {
                    return@fetch buildSpreadPage(getSpreadAnchor(current))
                }

                // Navigate by spreads from current
                var page = current
                val step = if (index > 0) 1 else -1
                repeat(abs(index)) {
                    page = nextPage(page, step) ?: return@fetch null
                }

                return@fetch buildSpreadPage(page)
            }

            onTap = onTap@{ offset ->
                // The retry button on a failed page comes first: it is drawn inside whatever
                // navigation region it happens to land in, and turning the page instead would
                // make it untappable.
                val failed = (currentPage as? ViewerReaderPage)?.imagePage as? ErrorPage
                if (failed?.retryIfHit(offset.x, offset.y) == true) return@onTap

                when (config.navigator.getAction(PointF(offset.x, offset.y))) {
                    NavigationRegion.MENU -> activity.toggleMenu()
                    NavigationRegion.NEXT -> if (isReversed) moveToPrevious() else moveToNext()
                    NavigationRegion.PREV -> if (isReversed) moveToNext() else moveToPrevious()
                    NavigationRegion.RIGHT -> moveRight()
                    NavigationRegion.LEFT -> moveLeft()
                }
            }

            onLongTap = { _ ->
                if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                    (currentPage as? ViewerReaderPage)?.let {
                        // Both halves of a spread, so the page dialog can save or share the pair
                        // the way the standard pager does.
                        activity.onPageLongTap(it.page, spreadPartner(it)?.page)
                    }
                }
            }
        }

        // KMK --> the renderer ships two upscalers and nothing chose between them, so every reader
        // ran the cheap one. Assigning drops the tiles already built, which is why it is also
        // reapplied on change rather than only at startup.
        applyUpscaler(config.upscaling)
        config.upscalingChangedListener = { applyUpscaler(it) }

        applyColorLut(config.colorLut, config.colorLutIntensity)
        config.colorLutChangedListener = { applyColorLut(config.colorLut, config.colorLutIntensity) }

        applyTone(config.grayscale, config.invertedColors)
        config.toneChangedListener = { applyTone(config.grayscale, config.invertedColors) }

        // blank space in the long strip clears to the reader background rather than black
        (pager.state as? ImageViewerContinuousState)?.backgroundColor = readerBackgroundColor()
        // KMK <--

        config.imagePropertyChangedListener = {
            // A theme change comes through here.
            cachedBackgroundColor = null
            cachedOnBackgroundColor = null
            // KMK -->
            (pager.state as? ImageViewerContinuousState)?.backgroundColor = readerBackgroundColor()
            // KMK <--

            val isDual = isDualPageMode()
            pager.state.apply {
                transition = when (if (isDual) config.transitionAnimationDual else config.transitionAnimation) {
                    TransitionAnimation.BASIC -> if (isVertical) TransitionBasic.Vertical else TransitionBasic
                    TransitionAnimation.FLIP -> TransitionFlip
                    TransitionAnimation.FLIP_LEFT -> TransitionFlipLeft
                    TransitionAnimation.FLIP_RIGHT -> TransitionFlipRight
                    TransitionAnimation.STACK_LEFT -> TransitionStackLeft
                    TransitionAnimation.STACK_RIGHT -> TransitionStackRight
                    TransitionAnimation.STACK_UP -> TransitionStackUp
                    TransitionAnimation.STACK_DOWN -> TransitionStackDown
                    TransitionAnimation.SPHERE -> TransitionSphere
                    TransitionAnimation.CUBE_INSIDE -> TransitionCube
                    TransitionAnimation.CUBE_OUTSIDE -> TransitionCubeOuter
                    TransitionAnimation.FADE -> TransitionFade
                    TransitionAnimation.FADE_WHITE -> TransitionFadeWhite
                    TransitionAnimation.NONE -> TransitionNone
                }

                when (if (isDual) config.cutoutModeDual else config.cutoutMode) {
                    ReaderPreferences.CutoutMode.IGNORE -> avoidCutout = false
                    ReaderPreferences.CutoutMode.AVOID -> {
                        avoidCutout = true
                        alwaysAvoidCutout = false
                    }

                    ReaderPreferences.CutoutMode.SHIFT -> {
                        avoidCutout = true
                        alwaysAvoidCutout = true
                    }
                }

                doubleTapZoomEnabled = config.doubleTapZoom
                pinchZoomEnabled = config.pinchZoom

                (this as? ImageViewerContinuousState)?.let {
                    homeScale = config.continuousMinWidth / 100f
                    scale = homeScale
                    minScale = if (config.zoomOutDisabled) 0f else 0.1f

                    (this@WebGpuViewer as? WebGpuViewerContinuous)?.let {
                        if (this@WebGpuViewer.useGap) {
                            pageGap = config.continuousGap / 100f
                        }
                    }
                }
            }

            synchronized(lock) {
                decodeQueue.clear()
                pageCache.values.forEach {
                    it.state = PageState.IDLE
                    (it as? ViewerReaderPage)?.spreadPage?.let(::cleanupImage)
                    cleanupImage(it.imagePage)
                }
                pageCache.clear()

                currentPage = (currentPage as? ViewerReaderPage)?.page?.let { getPage(it) }
                    ?: (currentPage as? ViewerTransitionPage)?.let {
                        getPage(it.prevChapter, it.nextChapter)
                    }

                currentPage?.let { preloadPages(it) }
            }

            pager.state.invalidate()
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        // KMK --> nothing draws once the device is lost, so the activity swaps in a standard viewer
        WebGpuRenderer.onDeviceLost = ::switchAwayFromWebGpu
        // KMK <--
    }

    // KMK -->
    private fun switchAwayFromWebGpu() = activity.onWebGpuDeviceLost()
    // KMK <--

    override fun destroy() {
        // KMK --> a replacement viewer registers before this one is destroyed; leave its listener be.
        // Bound references to the same function on the same viewer compare equal.
        if (WebGpuRenderer.onDeviceLost == ::switchAwayFromWebGpu) WebGpuRenderer.onDeviceLost = null
        // KMK <--
        // Before the interrupt: taken mid-decode, only the flag stops the worker parking.
        destroyed = true
        scope.cancel()

        // KMK: the decode threads are shared, so nothing is shut down; notifyAll below wakes the worker, which sees
        // destroyed and returns its thread to the pool.

        synchronized(lock) {
            decodeQueue.clear()
            // Nothing can still be animating, so the pin has nothing left to protect.
            pinnedFromPage = null
            pageCache.values.forEach {
                it.state = PageState.IDLE
                (it as? ViewerReaderPage)?.spreadPage?.cleanup()
                it.imagePage.cleanup()
            }
            pageCache.clear()
            deferredCleanup.forEach { it.cleanup() }
            deferredCleanup.clear()
            loneIndices.clear()
            chapterPreloadsInFlight.clear()
            lock.notifyAll()
        }
    }

    override fun getView(): View = pager

    /** Downloads [page] if needed, then re-queues it for decode once ready. */
    private fun startPageLoad(page: ViewerReaderPage) {
        val loader = page.page.chapter.pageLoader ?: run {
            synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
            return
        }

        if (page.page.status == Page.State.Ready) {
            synchronized(lock) {
                if (pageInCache(page) && !page.isDecoded) {
                    page.state = PageState.IDLE
                    queueForDecode(page, prioritize = currentPage?.let { pageKey(it) == pageKey(page) } ?: false)
                } else if (pageInCache(page)) {
                    page.state = PageState.IDLE
                }
            }
            return
        }

        synchronized(lock) {
            if (!pageInCache(page)) return
            page.state = PageState.LOADING
        }

        if (page.page.status == Page.State.Queue) {
            scope.launch(Dispatchers.IO) {
                loader.loadPage(page.page)
            }
        }

        scope.launch {
            try {
                val downloadProgressJob = launch {
                    page.page.progressFlow.collect { value ->
                        // Set under the lookup's lock, or an eviction's cleanup() lands between.
                        synchronized(lock) {
                            if (!pageInCache(page)) return@collect
                            (page.imagePage as? ProgressPage)?.progress = value / 100f
                        }
                    }
                }

                page.page.statusFlow.takeWhile { state ->
                    // Evicted: stop watching, rather than holding the page until the download ends.
                    if (!synchronized(lock) { pageInCache(page) }) return@takeWhile false

                    when (state) {
                        Page.State.Queue, Page.State.LoadPage, Page.State.DownloadImage -> true
                        is Page.State.Error -> {
                            logcat(LogPriority.ERROR) { "Page load error: ${state.error}" }
                            // Without this the page keeps its progress indicator for the rest of
                            // the session: nothing queues a decode for a page that never arrived.
                            synchronized(lock) {
                                if (pageInCache(page) && !page.imagePage.destroyed) {
                                    val old = page.imagePage
                                    page.imagePage = ErrorPage(
                                        state.error?.message
                                            ?: activity.stringResource(MR.strings.decode_image_error),
                                        page.spreadPosition,
                                        page,
                                    )
                                    page.page.markDisplayFailed()
                                    cleanupImage(old)
                                    page.imagePage.invalidate()
                                }
                            }
                            false
                        }

                        Page.State.Ready -> false
                    }
                }.collect {}

                downloadProgressJob.cancel()

                synchronized(lock) {
                    if (pageInCache(page) && page.state == PageState.LOADING) {
                        page.state = PageState.IDLE
                        if (page.page.status == Page.State.Ready && !page.isDecoded) {
                            queueForDecode(
                                page,
                                prioritize = currentPage?.let { pageKey(it) == pageKey(page) } ?: false,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "startPageLoad error" }
                synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
            }
        }
    }

    /**
     * Crops [image] to the half [page] stands for, when the reader splits pages this wide. Left
     * alone otherwise, including for a page that turns out not to be wide after all, which then
     * simply has no second half to go to.
     */
    private fun applySplitTrim(page: ViewerReaderPage, image: Image) {
        val half = page.half ?: return
        if (!config.dualPageSplit || isDualPageMode()) return
        if (image.width <= image.height) return

        // The first half is the side the reader starts on: the right of a right-to-left read.
        // The invert switch swaps that, as it does in the standard viewers.
        val firstIsLeft = !isReversed != config.dualPageInvert
        val takeLeft = (half == SplitHalf.FIRST) == firstIsLeft

        val middle = image.width / 2
        val side = if (takeLeft) {
            Rect(0, 0, middle, image.height)
        } else {
            Rect(middle, 0, image.width, image.height)
        }

        // Border trimming, if it ran, already narrowed the image; keep both. Rect.intersect
        // narrows in place and leaves the rect alone when there is no overlap, which is the right
        // answer anyway: the plain half.
        image.trim?.let { side.intersect(it) }
        image.trim = side
    }

    private suspend fun decodeReaderPage(page: ViewerReaderPage) {
        if (page.page.status != Page.State.Ready) {
            startPageLoad(page)
            return
        }

        val stream = page.page.stream?.invoke() ?: run {
            synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
            return
        }

        stream.use { input ->
            // Not evicted, and not already decoded by a concurrent call.
            synchronized(lock) {
                if (!pageInCache(page) || page.isDecoded) {
                    if (pageInCache(page)) page.state = PageState.IDLE
                    return
                }
            }

            // Buffered to read the spread tag, then decoded from the buffer. On the preference,
            // not isDualPageMode(): WIDE is portrait-off, and a rotate never re-decodes. Never in
            // continuous, where nothing pairs - that mode reads the stream instead of holding it.
            val bytes = if (!isContinuous && config.dualPageView != ReaderPreferences.DualPageView.NEVER) {
                input.readBytes()
            } else {
                null
            }

            // Left untouched for a file that names no side - [spreadPosition] then derives one.
            if (bytes != null) {
                val tag = Kim.readMetadata(bytes.inputStream(), bytes.size.toLong())
                    ?.findStringValue(TiffTag.TIFF_TAG_PAGE_NAME)
                page.taggedSpreadPosition = when (tag) {
                    "Left" -> SpreadPosition.LEFT
                    "Right" -> SpreadPosition.RIGHT
                    null -> null
                    else -> SpreadPosition.SINGLE
                }
            }

            // The decoder hands the map over unapplied - see ImageDecoder.Gainmap - because how
            // much of it to use depends on the display, so the viewer applies it.
            fun ImageDecoder.DecodeResult.gainmapInput(): GainmapInput? = gainmap?.let {
                GainmapInput(
                    pixels = it.pixels,
                    width = it.width,
                    height = it.height,
                    channels = it.channels,
                    gamma = it.gamma,
                    minContentBoost = it.minContentBoost,
                    maxContentBoost = it.maxContentBoost,
                    offsetSdr = it.offsetSdr,
                    offsetHdr = it.offsetHdr,
                )
            }

            // KMK --> closed here rather than by the finalizer: the decoder keeps the encoded page in
            // native memory the collector cannot see, and a reader decodes page after page.
            // The decoded pixels are Java direct buffers, so they outlive it.
            val imagePage = ImageDecoder.new(bytes?.inputStream() ?: input).use { dec ->
                // KMK <--
                val pageCount = dec.pages

                if (pageCount == 0) throw Exception("No frames decoded")

                val backgroundColor = if (config.automaticBackground) null else readerBackgroundColor()

                val firstFrame = dec.decodeNext()

                if (pageCount == 1) {
                    // A page wider than it is tall reads better turned on its side than shrunk to
                    // fit. A gain map is a second buffer with its own size and would have to turn
                    // with it, so an HDR page carrying one is left alone rather than torn from it.
                    val turn = config.dualPageRotateToFit &&
                        firstFrame.width > firstFrame.height &&
                        firstFrame.gainmap == null
                    val turned = if (turn) {
                        ImageUtil.rotateQuarter(
                            firstFrame.image,
                            firstFrame.width,
                            firstFrame.height,
                            bytesPerPixel = if (firstFrame.isHdr) 8 else 4,
                            // 90 degrees is clockwise in the standard viewers, and their invert
                            // switch turns the other way.
                            clockwise = !config.dualPageRotateToFitInvert,
                        )
                    } else {
                        null
                    }

                    // Only trim when not animated and not in dual page mode
                    val trimColors = if (config.imageCropBorders && !isDualPageMode()) {
                        listOf(
                            floatArrayOf(1f, 1f, 1f),
                            floatArrayOf(0f, 0f, 0f),
                        )
                    } else {
                        null
                    }

                    val firstImage = Image(
                        turned ?: firstFrame.image,
                        if (turned != null) firstFrame.height else firstFrame.width,
                        if (turned != null) firstFrame.width else firstFrame.height,
                        createMipMaps = true,
                        trimColors = trimColors,
                        trimThreshold = 0.15f,
                        backgroundColor = backgroundColor,
                        hdr = firstFrame.isHdr,
                        hdrHeadroom = firstFrame.hdrHeadroom,
                        gainmap = firstFrame.gainmapInput(),
                    )

                    // KMK: a page the reader splits keeps its whole image and shows half of it.
                    // The renderer already draws only an image's trim rect, so the two halves are
                    // a crop each rather than two smaller decodes.
                    applySplitTrim(page, firstImage)

                    ImagePage.ImageSingle(firstImage)
                } else {
                    val frames = ArrayList<Pair<Image, Int>>(pageCount)

                    // Built frames hold uploaded textures, and ImageSingle owns the only teardown.
                    fun discardFrames() {
                        if (frames.isNotEmpty()) ImagePage.ImageSingle(frames).cleanup()
                    }

                    val firstImage = Image(
                        firstFrame.image,
                        firstFrame.width,
                        firstFrame.height,
                        createMipMaps = false,
                        backgroundColor = backgroundColor,
                        hdr = firstFrame.isHdr,
                        hdrHeadroom = firstFrame.hdrHeadroom,
                        gainmap = firstFrame.gainmapInput(),
                    )

                    frames.add(Pair(firstImage, firstFrame.duration))

                    try {
                        for (i in 1 until pageCount) {
                            // Under lock: a decode this long gives an eviction's cleanup() time to land.
                            val stillWanted = synchronized(lock) {
                                pageInCache(page).also { inCache ->
                                    if (inCache) {
                                        (page.imagePage as? ProgressPage)?.progress = i.toFloat() / pageCount
                                    }
                                }
                            }

                            // Scrolled past: the frames left are work nothing will draw.
                            if (!stillWanted) {
                                discardFrames()
                                return
                            }

                            val frame = dec.decodeNext()
                            val image = Image(
                                frame.image,
                                frame.width,
                                frame.height,
                                createMipMaps = false,
                                backgroundColor = firstImage.backgroundColor,
                                hdr = frame.isHdr,
                                hdrHeadroom = frame.hdrHeadroom,
                                gainmap = frame.gainmapInput(),
                            )
                            frames.add(Pair(image, frame.duration))
                        }
                    } catch (e: Throwable) {
                        discardFrames()
                        throw e
                    }

                    ImagePage.ImageSingle(frames)
                }
            }

            synchronized(lock) {
                if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                    val oldImagePage = page.imagePage
                    page.imagePage = imagePage
                    // KMK -->
                    page.page.markDisplayed()
                    // KMK <--
                    noteIfLone(page)
                    page.state = PageState.IDLE
                    cleanupImage(oldImagePage)
                    // Fade up from the placeholder's colour, if that placeholder was on screen -
                    // one that decoded out of view has nothing left to fade from.
                    if (oldImagePage.isOnScreen) imagePage.fadeIn()
                    if (!isDualPageMode()) {
                        (page.imagePage as? ImagePage.ImageSingle)?.let {
                            if (!applyWideZoomIfNeeded(it)) {
                                applyFitModeAnchor(it)
                            }
                        }
                    }
                    pager.state.invalidate()
                } else {
                    if (pageInCache(page)) page.state = PageState.IDLE
                    imagePage.cleanup()
                }
            }
        }
    }

    private fun applyWideZoomIfNeeded(page: ImagePage.ImageSingle): Boolean {
        if (!config.landscapeZoom) return false

        val screenW = pager.state.width
        val screenH = pager.state.viewportHeight
        if (screenW <= 0 || screenH <= 0) return false

        // don't zoom if it fits at original scale
        if (page.trimWidth <= screenW) return false

        val image = page.image ?: return false

        val aspectRatio = min(
            page.trimWidth.toFloat() / page.trimHeight.toFloat(),
            image.width.toFloat() / image.height.toFloat(),
        )

        // not wide enough
        if (aspectRatio < 1.1) return false

        // Wide page: half the image width is wider than the screen aspect ratio
        if (aspectRatio <= 2f * screenW.toFloat() / screenH) return false

        page.parent = pager.state

        // Half the image width fills the screen width.
        page.homeScale = screenW.toFloat() / (page.trimWidth / 2f)

        page.scale = page.homeScale

        val minX = page.minX(page.homeScale)
        val maxX = page.maxX(page.homeScale)

        page.x = when (config.imageZoomType) {
            ZoomStartPosition.LEFT -> maxX
            ZoomStartPosition.RIGHT -> minX
            ZoomStartPosition.CENTER -> 0f
        }

        page.y = page.homeY

        return true
    }

    private fun applyFitModeAnchor(page: ImagePage.ImageSingle) {
        val scaleType = config.imageScaleType
        if (scaleType != 3 && scaleType != 4 && scaleType != 5) return

        val screenW = pager.state.width
        val screenH = pager.state.viewportHeight
        if (screenW <= 0 || screenH <= 0) return

        val w = page.trimWidth.toFloat()
        val h = page.trimHeight.toFloat()
        if (w <= 0f || h <= 0f) return

        page.parent = pager.state

        page.homeScale = when (scaleType) {
            3 -> screenW / w
            4 -> screenH / h
            else -> 1f // original size
        }.coerceAtLeast(0.01f)

        page.scale = page.homeScale

        if (scaleType == 5) { // original size
            if (page.homeScale < page.minScale) {
                page.minScale = page.homeScale
            }
        }

        val minX = page.minX(page.homeScale)
        val maxX = page.maxX(page.homeScale)

        page.x = when (config.imageZoomType) {
            ZoomStartPosition.LEFT -> maxX
            ZoomStartPosition.RIGHT -> minX
            ZoomStartPosition.CENTER -> 0f
        }

        page.y = page.homeY
    }

    protected fun preloadPage(page: ViewerPage, prioritize: Boolean = false) {
        synchronized(lock) {
            val cachedPage = findInCache(pageKey(page)) ?: return
            if (cachedPage is ViewerReaderPage) {
                queueForDecode(cachedPage, prioritize)
            }
        }
    }

    protected fun preloadPages(page: ViewerPage) {
        // page may be a stale copy - resolve the live cache entry.
        val key = pageKey(page)
        val cachedPage = synchronized(lock) { findInCache(key) } ?: return

        // prev, then next, then current+partner - the last prioritized call ends up highest.
        val prevPages = mutableListOf<ViewerPage>()
        var p: ViewerPage? = cachedPage
        for (i in 0 until preloadBehind) {
            p = p?.prev ?: break
            prevPages.add(p)
        }
        prevPages.asReversed().forEach { preloadPage(it) }

        val nextPages = mutableListOf<ViewerPage>()
        p = cachedPage
        for (i in 0 until preloadAhead) {
            p = p?.next ?: break
            nextPages.add(p)
        }
        nextPages.asReversed().forEach { preloadPage(it) }

        cachedPage.next?.let { preloadPage(it, prioritize = true) }
        preloadPage(cachedPage, prioritize = true)
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        // Empty too: lastIndex would be -1, and the requested page is read from it.
        val pages = chapters.currChapter.pages
        if (pages.isNullOrEmpty()) return

        this.viewerChapters = chapters

        // Only when nothing shows yet - re-setting chapters must not move the page.
        val page = currentPage ?: getPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
        val anchor = getSpreadAnchor(page)
        currentPage = anchor
        progressPage(anchor)?.let { activity.onPageSelected(it.page) }
        preloadPages(anchor)

        pager.state.apply {
            onPageChange = onPageChange@{ delta ->
                // The viewer already showed the page at fetchPage(delta).
                // We need to update currentPage to match that.
                val current = currentPage ?: return@onPageChange

                // Navigate the same way fetchPage does
                var page = current
                val step = if (delta > 0) 1 else -1
                repeat(abs(delta)) {
                    page = nextPage(page, step) ?: return@onPageChange
                }

                // Synchronous, since the viewer walks getPage() from here - stale, and the next
                // scroll step crosses the same boundary again.
                currentPage = page

                // The rest ran here too, on the animation thread under the viewer's scroll lock.
                // Posted in order, so nothing is skipped or reordered - and on this viewer's own
                // MainScope, not the state's: that one dispatches inside the frame callback.
                val settled = page
                this@WebGpuViewer.scope.launch {
                    activity.hideMenu()
                    progressPage(settled)?.let { activity.onPageSelected(it.page) }
                    preloadPages(settled)

                    (settled as? ViewerTransitionPage)?.let { transitionPage ->
                        if (transitionPage.prevChapter == null || transitionPage.nextChapter == null) {
                            activity.showMenu()
                        }
                    }
                }
            }

            invalidate()
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     * In dual page mode, aligns to the start of the spread containing the page.
     */
    override fun moveToPage(page: ReaderPage) {
        // Pin first: resolving a target outside the cached window trims the cache.
        pinnedFromPage = currentPage?.let { buildSpreadPage(it) }
        moveToPage(getSpreadAnchor(getPage(page)))
    }

    private fun moveToPage(newPage: ViewerPage) {
        val previousPage = currentPage
        // Before preloadPages below trims the cache - see [pinnedFromPage].
        val fromSpread = previousPage?.let { buildSpreadPage(it) }
        pinnedFromPage = fromSpread
        synchronized(lock) { flushDeferredCleanup() }

        currentPage = newPage
        progressPage(newPage)?.let { activity.onPageSelected(it.page) }
        preloadPages(newPage)

        (newPage as? ViewerTransitionPage)?.let { ViewerTransitionPage ->
            if (ViewerTransitionPage.prevChapter == null || ViewerTransitionPage.nextChapter == null) {
                activity.showMenu()
            }
        }

        if (previousPage == null) return

        val direction = when (previousPage) {
            is ViewerReaderPage if newPage is ViewerReaderPage -> if (previousPage.page.chapter ==
                newPage.page.chapter
            ) {
                (newPage.page.index - previousPage.page.index).coerceIn(-1, 1)
            } else if (previousPage.page.chapter == newPage.prevChapter) {
                1
            } else {
                -1
            }

            is ViewerTransitionPage if newPage is ViewerReaderPage -> if (previousPage.nextChapter ==
                newPage.page.chapter
            ) {
                1
            } else {
                -1
            }

            is ViewerReaderPage if newPage is ViewerTransitionPage -> if (previousPage.page.chapter ==
                newPage.prevChapter
            ) {
                1
            } else {
                -1
            }

            else -> 0
        }

        if (direction != 0 && fromSpread != null) {
            animateTurn(direction, fromSpread)
        } else {
            pager.state.invalidate()
        }
    }

    /** How a [moveToPage] turn is shown. [direction] is 1 forward through the pages, -1 back. */
    protected open fun animateTurn(direction: Int, fromSpread: ImagePage) {
        pager.state.transitionFromPage = fromSpread
        pager.state.animatePageTurn(if (isReversed) direction else -direction)
    }

    fun moveToNext() {
        moveRight()
    }

    fun moveToPrevious() {
        moveLeft()
    }

    protected open fun moveRight() {
        pager.state.getPage(0)?.let { page ->
            if (config.navigateToPan) {
                val minX = page.minX(page.scale)
                val maxX = page.maxX(page.scale)
                // Where a running pan is headed, else where it sits.
                val currentX = page.animationTargetX ?: page.x

                val c = if (isVertical && config.imageZoomType == ZoomStartPosition.RIGHT) -1 else 1
                val x = (currentX - c / page.scale).coerceIn(minX, maxX)

                if (!currentX.closeTo(x)) {
                    page.animateTo(targetX = x, targetY = page.y)
                    return
                }
            }

            navigateSpread(if (isReversed) -1 else 1)
        }
    }

    protected open fun moveLeft() {
        pager.state.getPage(0)?.let { page ->
            if (config.navigateToPan) {
                val minX = page.minX(page.scale)
                val maxX = page.maxX(page.scale)
                val currentX = page.animationTargetX ?: page.x

                val c = if (isVertical && config.imageZoomType == ZoomStartPosition.RIGHT) -1 else 1
                val x = (currentX + c / page.scale).coerceIn(minX, maxX)

                if (!currentX.closeTo(x)) {
                    page.animateTo(targetX = x, targetY = page.y)
                    return
                }
            }

            navigateSpread(if (isReversed) 1 else -1)
        }
    }

    /** Target anchor page one spread past [from], in [direction] (positive = forward). */
    private fun nextPage(from: ViewerPage, direction: Int): ViewerPage? {
        var page = getSpreadAnchor(from)

        page = if (direction > 0) {
            if (page is ViewerReaderPage && spreadPartner(page) != null) {
                page.next?.next ?: return null
            } else {
                page.next ?: return null
            }
        } else {
            page.prev ?: return null
        }

        return getSpreadAnchor(page)
    }

    private fun navigateSpread(direction: Int) {
        val target = currentPage?.let { nextPage(it, direction) } ?: return
        moveToPage(target)
    }

    protected fun moveUp() {
        moveToPrevious()
    }

    protected fun moveDown() {
        moveToNext()
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted.xor(isReversed)) moveDown() else moveUp()
                }
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted.xor(isReversed)) moveUp() else moveDown()
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isUp) if (ctrlPressed) moveToNext() else moveRight()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isUp) if (ctrlPressed) moveToPrevious() else moveLeft()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }
}
