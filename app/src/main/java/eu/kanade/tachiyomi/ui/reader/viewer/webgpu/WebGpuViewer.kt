package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.compose.animation.core.Spring
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
import ca.mpreg.webgpuviewer.renderer.DeviceMemory
import ca.mpreg.webgpuviewer.renderer.GainmapInput
import ca.mpreg.webgpuviewer.renderer.Image
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
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TransitionAnimation
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
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

/**
 * Decode workers each viewer runs. Half the cores, and never more than three - the viewer's init
 * block has why more of them would cost the page being read rather than help it.
 */
private val decodeWorkers = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)
// KMK <--

open class WebGpuViewer(
    val activity: ReaderActivity,
    val isReversed: Boolean,
    val isVertical: Boolean,
    val pager: ImageView = ImageView(activity, isVertical = isVertical, isReversed = isReversed),
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
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

    // KMK -->
    /** Draws the reader's own views for the pages that are not images - see [WebGpuPageViews]. */
    private val pageViews by lazy { WebGpuPageViews(activity, seedColor) }

    /** The progress indicator's colours, once the theme has been read. Nothing spins before. */
    @Volatile
    private var indicatorColors: IndicatorColors? = null

    private fun loadIndicatorColors() {
        scope.launch {
            indicatorColors = try {
                pageViews.indicatorColors()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not read the progress indicator colours" }
                null
            }
        }
    }

    /** The page a press is on, from [ImageViewerState.onPress] until it ends. Main thread only. */
    private var pressedPage: SnapshotPage? = null
    // KMK <--

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
        // KMK --> one worker decoded every page in turn, so a page waited out the whole of the
        // page before it - that page's upload included, which lands on the renderer's single
        // thread and leaves the decode threads with nothing to do meanwhile. A few workers let
        // one page's decode overlap the last one's upload.
        //
        // Deliberately few. The queue is LIFO, so the page being looked at is always taken first,
        // and every extra worker is another full-size image held at once and a core taken from
        // that page to decode one nobody is waiting for yet.
        repeat(decodeWorkers) {
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
                                    page.page.markDisplayFailed()
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
                    // A stray interrupt; destroy() wakes the workers with notifyAll instead.
                } catch (_: CancellationException) {
                    // Scope cancelled with the viewer.
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Decode worker died" }
                }
            }
        }
        // KMK <--
    }

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = WebGpuConfig(this, scope, readerPreferences)

    // Read from the render and decode threads, via the prevChapter/nextChapter getters.
    @Volatile
    var viewerChapters: ViewerChapters? = null

    val pages: List<ReaderPage>? get() = (currentPage as? ViewerReaderPage)?.page?.chapter?.pages

    // KMK: the reader page behind [currentPage], for a host that works in reader pages rather than
    // this viewer's own. Page boosting asks for it and had no way to reach it.
    val currentReaderPage: ReaderPage? get() = (currentPage as? ViewerReaderPage)?.page

    /**
     * KMK: one auto scroll step. A paged mode turns a page, which is what the standard paged
     * viewer does per interval. [WebGpuViewerContinuous] overrides it to cover a whole screen,
     * the distance the standard long strip viewer covers - a forward tap's half viewport would
     * crawl at the same interval setting.
     */
    open fun autoScrollStep() = moveToNext()

    /**
     * KMK: a strip's home scale - the chosen width, then capped to an aspect ratio the way the
     * standard long strip viewer caps it. Without the cap a wide screen stretches a strip across
     * its whole width whatever shape the page is, which is what the setting exists to stop.
     *
     * Returns the chosen width unchanged when there is nothing to measure against yet; the layout
     * listener runs this again once the surface has a size.
     */
    protected fun continuousHomeScale(state: ImageViewerContinuousState): Float {
        val chosen = config.continuousMinWidth / 100f
        val scaleType = config.webtoonScaleType
        if (scaleType == ReaderPreferences.WebtoonScaleType.FIT) return chosen
        // A gapped strip takes the cap only with smart scale on, as in the standard viewer.
        if (useGap && !config.longStripGapSmartScale) return chosen

        val width = state.width.toFloat()
        val height = state.viewportHeight
        if (width <= 0f || height <= 0f) return chosen

        val desired = scaleType.ratio
        if (desired <= 0f || width / height <= desired) return chosen
        return min(chosen, height * desired / width)
    }

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

    // KMK -->
    /**
     * The page types below that draw more than an image: a page still loading, one that failed and a
     * chapter transition. They are sized to the viewport rather than to anything decoded, never zoom,
     * and share the Material progress indicator the standard viewers show.
     */
    abstract inner class ReaderRenderPage : ImagePage.Render(0, 0) {
        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        override val backgroundColor: Int = readerBackgroundColor()

        /** When this page's indicator started turning. */
        private val indicatorStartedAt = SystemClock.uptimeMillis()

        // The determinate arc's spring, render thread only.
        private var springValue = 0f
        private var springVelocity = 0f
        private var springAt = 0L

        private fun dp(value: Float): Float = with(pager.state.density) { value.dp.toPx() }

        /**
         * Draws [MaterialSpinner] centred on [cx]/[cy]: indeterminate while [progress] is 0, the
         * turning determinate ring after, as the combined indicator switches between them. It moves
         * every frame, so it asks for the next one.
         */
        protected fun drawIndicator(dst: GPUTexture, cx: Float, cy: Float, scale: Float, progress: Float) {
            val colors = indicatorColors ?: return invalidate()
            val stroke = scale * dp(MaterialSpinner.STROKE_DP)
            // drawArc's rect sits half a stroke inside the indicator's bounds.
            val radius = scale * (dp(MaterialSpinner.DIAMETER_DP) - dp(MaterialSpinner.STROKE_DP)) / 2f
            val now = SystemClock.uptimeMillis()
            val elapsed = now - indicatorStartedAt

            if (progress <= 0f) {
                springAt = 0L
                val indicator = MaterialSpinner.indeterminate(elapsed)
                arc(dst, cx, cy, radius, stroke, indicator.start, indicator.sweep, colors.indicator)
            } else {
                val (indicator, track) = MaterialSpinner.determinate(elapsed, animateProgress(progress, now))
                arc(dst, cx, cy, radius, stroke, track.start, track.sweep, colors.track)
                arc(dst, cx, cy, radius, stroke, indicator.start, indicator.sweep, colors.indicator)
            }
            invalidate()
        }

        /**
         * [ProgressIndicatorDefaults.ProgressAnimationSpec]: a spring with no bounce and very low
         * stiffness, starting from the first progress it is given as animateFloatAsState does.
         */
        private fun animateProgress(target: Float, now: Long): Float {
            if (springAt == 0L) {
                springAt = now
                springValue = target
                springVelocity = 0f
                return target
            }
            val dt = ((now - springAt) / 1000f).coerceIn(0f, 0.1f)
            springAt = now
            // Critically damped, stiffness 50 and unit mass.
            val omega = sqrt(Spring.StiffnessVeryLow)
            val delta = springValue - target
            val decay = exp(-omega * dt)
            val carried = springVelocity + omega * delta
            springValue = target + (delta + carried * dt) * decay
            springVelocity = (springVelocity - carried * omega * dt) * decay
            return springValue
        }
    }

    /**
     * A page showing one of the reader's own views, drawn into a bitmap by [pageViews] so it looks
     * the way the standard viewers show it, Material buttons included - and those buttons answer a
     * press as theirs do, through [ImageViewerState.onPress].
     *
     * The view is captured on the main thread whenever [captureKey] or the page's width changes, and
     * handed over to the render thread, which swaps it in and frees the one it replaces: that thread
     * is the only one drawing them, so it is the only one that can tell a capture is done with.
     */
    abstract inner class SnapshotPage : ReaderRenderPage() {
        /** What the view shows; a change takes a new capture. Read on the render thread. */
        protected abstract fun captureKey(): Any

        /** Draws the view [width] pixels wide, on the main thread. */
        protected abstract suspend fun capture(width: Int): PageSnapshot

        /** Drawn over the capture once it is placed at [left]/[top], in [dst]'s pixels. */
        protected open fun renderOver(dst: GPUTexture, left: Float, top: Float, scale: Float, snapshot: PageSnapshot) {}

        private val captureLock = Any()

        /** The newest capture, not drawn yet. Guarded by [captureLock]. */
        private var captured: PageSnapshot? = null

        /** What was last captured, or is being. Guarded by [captureLock]. */
        private var requested: Pair<Any, Int>? = null
        private var captureJob: Job? = null

        /** The capture being drawn; swapped on the render thread. */
        @Volatile
        private var shown: PageSnapshot? = null

        /** How tall the view is at a scale of one, or 0 before the first capture. */
        protected val snapshotHeight: Int
            get() = synchronized(captureLock) { captured?.height } ?: shown?.height ?: 0

        /**
         * Where the buttons were last drawn live, as fractions of the surface - what
         * [ImageViewerState.onPress] reports. Never from a transition's cache seed, which draws the
         * page as if at rest rather than where it is.
         */
        @Volatile
        private var hitBoxes: List<SnapshotHitBox> = emptyList()

        @Volatile
        private var press: SnapshotPress? = null

        private fun requestCapture(width: Int) {
            if (width <= 0) return
            val key = captureKey() to width
            synchronized(captureLock) {
                if (key == requested || destroyed) return
                requested = key
                captureJob?.cancel()
                captureJob = this@WebGpuViewer.scope.launch {
                    val snapshot = try {
                        capture(width)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Could not draw a page view" }
                        return@launch
                    }
                    synchronized(captureLock) {
                        if (destroyed || requested != key) {
                            snapshot.texture.release()
                            return@launch
                        }
                        // Superseded before it was ever drawn.
                        captured?.texture?.release()
                        captured = snapshot
                    }
                    invalidate()
                }
            }
        }

        /** True when [x]/[y], fractions of the surface, land on a button this page drew live. */
        fun pressAt(x: Float, y: Float): Boolean {
            if (!pager.state.isOnScreen(this)) return false
            val hit = hitBoxes.firstOrNull { it.bounds.contains(x, y) } ?: return false
            press = SnapshotPress(hit.button, x, y, SystemClock.uptimeMillis())
            invalidate()
            return true
        }

        /** Ends the press [pressAt] started, running the button's action if it was a click. */
        fun endPress(clicked: Boolean) {
            val current = press ?: return
            current.upAt = SystemClock.uptimeMillis()
            invalidate()
            if (clicked) current.button.onClick()
        }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            requestCapture(width)
            val next = synchronized(captureLock) { captured.also { captured = null } }
            if (next != null) {
                shown?.texture?.release()
                shown = next
            }
            val snapshot = shown ?: run {
                if (drawingLive) hitBoxes = emptyList()
                return
            }

            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)
            val drawnWidth = snapshot.width * scale
            val drawnHeight = snapshot.height * scale
            val left = cx - drawnWidth / 2f
            val top = cy - drawnHeight / 2f

            bitmap(
                snapshot.texture,
                left / dst.width,
                top / dst.height,
                (left + drawnWidth) / dst.width,
                (top + drawnHeight) / dst.height,
            )
            renderOver(dst, left, top, scale, snapshot)
            drawPress(dst, left, top, scale, snapshot)

            if (drawingLive) {
                hitBoxes = snapshot.buttons.map { button ->
                    val b = button.bounds
                    SnapshotHitBox(
                        RectF(
                            (left + b.left * scale) / dst.width,
                            (top + b.top * scale) / dst.height,
                            (left + b.right * scale) / dst.width,
                            (top + b.bottom * scale) / dst.height,
                        ),
                        button,
                    )
                }
            }
        }

        private fun drawPress(dst: GPUTexture, left: Float, top: Float, scale: Float, snapshot: PageSnapshot) {
            val current = press ?: return
            // A new capture's buttons are new objects: whatever was pressed is gone.
            if (current.button !in snapshot.buttons) {
                press = null
                return
            }
            val now = SystemClock.uptimeMillis()
            val b = current.button.bounds
            val bounds = RectF(left + b.left * scale, top + b.top * scale, left + b.right * scale, top + b.bottom * scale)
            val frame = ButtonRipple.frame(
                bounds,
                current.x * dst.width,
                current.y * dst.height,
                now - current.downAt,
                current.upAt.takeIf { it != 0L }?.let { now - it },
            )
            if (frame == null) {
                if (press === current) press = null
                return
            }
            val alpha = ((current.button.rippleColor ushr 24) * frame.opacity).roundToInt()
            roundRect(
                dst,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                current.button.cornerRadius * scale,
                color = 0,
                rippleX = frame.x,
                rippleY = frame.y,
                rippleRadius = frame.radius,
                rippleColor = (current.button.rippleColor and 0xFFFFFF) or (alpha shl 24),
            )
            invalidate()
        }

        override fun cleanup() {
            super.cleanup()
            synchronized(captureLock) {
                captureJob?.cancel()
                captured?.texture?.release()
                captured = null
            }
            // Its free waits for the render dispatcher, so a frame drawing it now is unaffected.
            shown?.texture?.release()
            hitBoxes = emptyList()
            press = null
        }
    }

    /** A page that failed to load or decode, as `reader_error.xml` shows it in the standard viewers. */
    inner class ErrorPage internal constructor(
        message: String,
        private val spreadPosition: SpreadPosition = SpreadPosition.SINGLE,
        /** The page that failed, for the retry and web view buttons. */
        private val failed: ViewerReaderPage? = null,
    ) : SnapshotPage() {
        override val width: Int
            get() = viewportPageWidth(spreadPosition != SpreadPosition.SINGLE)
        override val height: Int
            get() = max(pager.state.height, snapshotHeight)

        var message: String = message
            set(value) {
                field = value
                invalidate()
            }

        override fun captureKey(): Any = message

        override suspend fun capture(width: Int): PageSnapshot {
            // As PagerPageHolder: offered for any image url, working only for a web one.
            val imageUrl = failed?.page?.imageUrl
            return pageViews.error(
                message = message,
                width = width,
                onRetry = failed?.let { page -> { retryPage(page) } },
                onOpenInWebView = imageUrl?.takeIf { it.startsWith("http", true) }?.let { url -> { openInWebView(url) } },
                showOpenInWebView = imageUrl != null,
            )
        }
    }

    private fun openInWebView(url: String) {
        val sourceId = activity.viewModel.manga?.source
        activity.startActivity(WebViewActivity.newIntent(activity, url, sourceId))
    }

    /** A page still loading: the standard viewers' progress indicator, on the reader background. */
    inner class ProgressPage : ReaderRenderPage() {
        override val width: Int
            get() = viewportPageWidth(isDualPageMode())
        override val height: Int
            get() = pager.state.height

        var progress: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            // Its own footprint, so the page carries its background wherever a transition puts it.
            fillPage(dst, x, y, scale, backgroundColor)

            drawIndicator(
                dst,
                dst.width * (0.5f + scale * x),
                dst.height * (0.5f + scale * y),
                scale,
                progress.fastCoerceIn(0f, 1f),
            )
        }
    }

    /**
     * The page between two chapters, as the standard viewers show it: the chapter transition card,
     * and below it the chapter being turned to loading, or failing with a retry button.
     */
    inner class TransitionPage(val prevChapter: ReaderChapter?, val nextChapter: ReaderChapter?) : SnapshotPage() {
        /** Square, and never a spread side - [buildSpreadPage] hands it back whole. Taller if the card needs it. */
        override val width: Int
            get() = min(pager.state.width, pager.state.height)
        override val height: Int
            get() = max(width, snapshotHeight)

        /** Either chapter loading, failing or finishing changes what the page shows. */
        private val stateJobs = listOfNotNull(prevChapter, nextChapter).map { chapter ->
            this@WebGpuViewer.scope.launch { chapter.stateFlow.collect { invalidate() } }
        }

        /**
         * Which way the transition reads: from the chapter being read. Arriving back from the later
         * chapter reads as the standard viewers' previous-chapter transition.
         */
        private fun transition(): ChapterTransition? = when {
            prevChapter == null -> nextChapter?.let { ChapterTransition.Prev(it, null) }
            nextChapter == null -> ChapterTransition.Next(prevChapter, null)
            viewerChapters?.currChapter === nextChapter -> ChapterTransition.Prev(nextChapter, prevChapter)
            else -> ChapterTransition.Next(prevChapter, nextChapter)
        }

        override fun captureKey(): Any {
            val transition = transition()
            val state = transition?.to?.state
            return listOf(
                transition?.javaClass,
                transition?.from,
                transition?.to,
                state?.javaClass,
                (state as? ReaderChapter.State.Error)?.error?.message,
            )
        }

        override suspend fun capture(width: Int): PageSnapshot {
            val transition = transition() ?: error("a transition with no chapter either side")
            return pageViews.transition(transition, width, isContinuous) { chapter ->
                activity.requestPreloadChapter(chapter)
            }
        }

        override fun renderOver(dst: GPUTexture, left: Float, top: Float, scale: Float, snapshot: PageSnapshot) {
            val center = snapshot.spinnerCenter ?: return
            if (transition()?.to?.state !is ReaderChapter.State.Loading) return
            drawIndicator(dst, left + center.x * scale, top + center.y * scale, scale, 0f)
        }

        override fun cleanup() {
            stateJobs.forEach { it.cancel() }
            super.cleanup()
        }
    }
    // KMK <--

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

            // KMK --> a button drawn on a page takes the touch as it lands, before it can become a
            // tap on whatever navigation region the button sits in - see SnapshotPage.
            onPress = { offset ->
                val pages = synchronized(lock) { pageCache.values.map { it.imagePage } }
                pressedPage = pages.filterIsInstance<SnapshotPage>().firstOrNull { it.pressAt(offset.x, offset.y) }
                pressedPage != null
            }
            onPressEnd = { _, clicked ->
                pressedPage?.endPress(clicked)
                pressedPage = null
            }
            // KMK <--

            onTap = onTap@{ offset ->
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

        // KMK -->
        loadIndicatorColors()

        applyColorLut(config.colorLut, config.colorLutIntensity)
        config.colorLutChangedListener = { applyColorLut(config.colorLut, config.colorLutIntensity) }

        applyTone(config.grayscale, config.invertedColors)
        config.toneChangedListener = { applyTone(config.grayscale, config.invertedColors) }

        // blank space in the long strip clears to the reader background rather than black
        (pager.state as? ImageViewerContinuousState)?.backgroundColor = readerBackgroundColor()

        // The aspect cap measures the viewport, and a rotation changes that without any preference
        // changing - so recompute on a real size change rather than only when a setting moves.
        pager.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sameSize = right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop
            if (!sameSize) {
                (pager.state as? ImageViewerContinuousState)?.let { st ->
                    val next = continuousHomeScale(st)
                    if (next != st.homeScale) {
                        // Only follow the new home when the reader is sitting at it; a reader who
                        // zoomed in keeps the zoom a rotation found them at.
                        val wasHome = st.scale == st.homeScale
                        st.homeScale = next
                        if (wasHome) st.scale = next
                        st.invalidate()
                    }
                }
            }
        }
        // KMK <--

        config.imagePropertyChangedListener = {
            // A theme change comes through here.
            cachedBackgroundColor = null
            cachedOnBackgroundColor = null
            // KMK -->
            loadIndicatorColors()
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
                    homeScale = continuousHomeScale(it)
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
        // KMK -->
        pageViews.destroy()
        // KMK <--

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
                    // fit. A gain map is a second buffer with its own size, so it turns with the
                    // base rather than the page being left flat for carrying one.
                    val gainmapIn = firstFrame.gainmapInput()
                    // The map is spatially aligned with the base, so the two can only turn
                    // together. rotateQuarter needs it packed at channels bytes per pixel; if it
                    // is laid out any other way the page stays as it is, which is what it did
                    // before, rather than turning out of step with its own map.
                    val gainmapTurnable = gainmapIn == null ||
                        gainmapIn.pixels.capacity().toLong() ==
                        gainmapIn.width.toLong() * gainmapIn.height * gainmapIn.channels
                    // 90 degrees is clockwise in the standard viewers, and their invert switch
                    // turns the other way.
                    val clockwise = !config.dualPageRotateToFitInvert
                    val turn = config.dualPageRotateToFit &&
                        firstFrame.width > firstFrame.height &&
                        gainmapTurnable
                    val turned = if (turn) {
                        ImageUtil.rotateQuarter(
                            firstFrame.image,
                            firstFrame.width,
                            firstFrame.height,
                            bytesPerPixel = if (firstFrame.isHdr) 8 else 4,
                            clockwise = clockwise,
                        )
                    } else {
                        null
                    }
                    val turnedGainmap = if (turned != null && gainmapIn != null) {
                        GainmapInput(
                            pixels = ImageUtil.rotateQuarter(
                                gainmapIn.pixels,
                                gainmapIn.width,
                                gainmapIn.height,
                                bytesPerPixel = gainmapIn.channels,
                                clockwise = clockwise,
                            ),
                            width = gainmapIn.height,
                            height = gainmapIn.width,
                            channels = gainmapIn.channels,
                            gamma = gainmapIn.gamma,
                            minContentBoost = gainmapIn.minContentBoost,
                            maxContentBoost = gainmapIn.maxContentBoost,
                            offsetSdr = gainmapIn.offsetSdr,
                            offsetHdr = gainmapIn.offsetHdr,
                        )
                    } else {
                        gainmapIn
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
                        gainmap = turnedGainmap,
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

                    // KMK --> every frame is a full-resolution texture with no mipmaps behind it,
                    // and all of them stay resident for as long as the page is cached: a large
                    // page with many frames is hundreds of megabytes of GPU memory for one page.
                    // Past what the device can afford the animation loops over the frames that
                    // fit, rather than taking the app down for the ones that do not.
                    val frameBytes =
                        firstFrame.width.toLong() * firstFrame.height * if (firstFrame.isHdr) 8 else 4
                    val keepFrames = if (frameBytes <= 0L) {
                        pageCount
                    } else {
                        min(pageCount, (DeviceMemory.animatedPageBytes / frameBytes).toInt().coerceAtLeast(1))
                    }
                    if (keepFrames < pageCount) {
                        logcat(LogPriority.WARN) {
                            "animated page ${firstFrame.width}x${firstFrame.height} has $pageCount frames, " +
                                "keeping $keepFrames within ${DeviceMemory.animatedPageBytes / (1024 * 1024)} MB"
                        }
                    }
                    // KMK <--

                    try {
                        for (i in 1 until keepFrames) {
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
                    // KMK --> tapping through pages settles one page after another, and each
                    // settle hid the menu the tap had just opened. The standard pager guards the
                    // same call with this (PagerViewer), and Mihon fixed the renderer's copy in
                    // #3956 - taken here, where the call is not behind an isContinuous check.
                    if (!activity.isScrollingThroughPages) {
                        activity.hideMenu()
                    }
                    // KMK <--
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

// KMK -->
/** Where a [WebGpuViewer.SnapshotPage] button was drawn, as fractions of the surface. */
private class SnapshotHitBox(val bounds: RectF, val button: PageSnapshot.Button)

/** A press on a [WebGpuViewer.SnapshotPage] button, at fractions of the surface, for its ripple. */
private class SnapshotPress(val button: PageSnapshot.Button, val x: Float, val y: Float, val downAt: Long) {
    @Volatile
    var upAt = 0L
}
// KMK <--
