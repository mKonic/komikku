package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import ca.mpreg.webgpuviewer.draw.BitmapTexture
import com.google.android.material.button.MaterialButton
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderButton
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTransitionView
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.android.awaitFrame
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * KMK: a page view drawn into a bitmap for a WebGPU page to show, with where its buttons landed.
 * Sizes are the bitmap's own pixels, which are the page's at a scale of one.
 */
class PageSnapshot(
    val texture: BitmapTexture,
    val width: Int,
    val height: Int,
    val buttons: List<Button>,
    /** Where a progress indicator belongs, when the view left room for one. */
    val spinnerCenter: PointF?,
) {
    class Button(
        /** The button's own background, inside the touch insets a Material button keeps. */
        val bounds: RectF,
        val cornerRadius: Float,
        /** The colour its ripple draws in when pressed, alpha included. */
        val rippleColor: Int,
        val onClick: () -> Unit,
    )
}

/** KMK: the two colours a Material circular progress indicator draws with. */
class IndicatorColors(val indicator: Int, val track: Int)

/**
 * KMK: the reader's own page views - the chapter transition card, the failed page and its buttons -
 * drawn into bitmaps for the WebGPU pages to show.
 *
 * The renderer draws on a surface no view can sit on top of, so those pages used to be put together
 * out of flat rects and text, which looked nothing like the standard viewers and had no press state
 * to show. Drawing the real views keeps the look - cards, icons, Material buttons - and a bitmap
 * turns, fades and scrolls with its page through every transition, where a view laid over the
 * surface could not follow.
 *
 * A composable only composes once it is attached to a window, so each view is laid out in [host],
 * which sits invisible behind the renderer, drawn, and taken straight back out.
 */
class WebGpuPageViews(
    private val activity: ReaderActivity,
    private val seedColor: Int?,
) {
    /** Resolved per capture: a change of reader theme swaps light and dark. */
    private val themedContext: Context get() = activity.createReaderThemeContext()

    private val host by lazy {
        FrameLayout(activity).apply { visibility = View.INVISIBLE }
    }

    private fun attachHost() {
        if (host.parent == null) {
            activity.binding.viewerContainer.addView(host, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    fun destroy() {
        (host.parent as? ViewGroup)?.removeView(host)
    }

    /**
     * The chapter transition as the standard pager shows it: [ReaderTransitionView], then while the
     * chapter being turned to loads, room for a progress indicator and the loading line, or if it
     * failed, the reason and a retry button. [continuous] takes the long strip's spacing instead.
     */
    suspend fun transition(
        transition: ChapterTransition,
        width: Int,
        continuous: Boolean,
        onRetry: (ReaderChapter) -> Unit,
    ): PageSnapshot {
        val context = themedContext
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            if (continuous) {
                setPadding(32.dpToPx, 128.dpToPx, 32.dpToPx, 128.dpToPx)
            } else {
                setPadding(64.dpToPx, 0, 64.dpToPx, 0)
            }
        }

        val transitionView = ReaderTransitionView(context, seedColor = seedColor)
        transitionView.bind(transition, Injekt.get<DownloadManager>(), activity.viewModel.manga)
        layout.addView(transitionView)

        val pages = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        val pagesParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (continuous) setMargins(0, 16.dpToPx, 0, 16.dpToPx)
        }
        layout.addView(pages, pagesParams)

        var spinner: View? = null
        val clicks = mutableMapOf<View, () -> Unit>()
        val target = transition.to
        when (val state = target?.state) {
            is ReaderChapter.State.Loading -> {
                // The indicator itself animates, so the page draws it; this only keeps its place.
                spinner = View(context)
                pages.addView(spinner, LinearLayout.LayoutParams(INDICATOR_DP.dpToPx, INDICATOR_DP.dpToPx))
                pages.addView(
                    AppCompatTextView(context).apply {
                        text = context.stringResource(MR.strings.transition_pages_loading)
                    },
                )
            }
            is ReaderChapter.State.Error -> {
                pages.addView(
                    AppCompatTextView(context).apply {
                        text = context.stringResource(MR.strings.transition_pages_error, state.error.message ?: "")
                    },
                )
                val retry = ReaderButton(context).apply {
                    text = context.stringResource(MR.strings.action_retry)
                }
                pages.addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                clicks[retry] = { onRetry(target) }
            }
            else -> {}
        }

        return capture(layout, width, clicks, spinner)
    }

    /** A page that failed, as `reader_error.xml` shows it in the standard viewers. */
    suspend fun error(
        message: String,
        width: Int,
        onRetry: (() -> Unit)?,
        onOpenInWebView: (() -> Unit)?,
        showOpenInWebView: Boolean,
    ): PageSnapshot {
        val binding = ReaderErrorBinding.inflate(LayoutInflater.from(themedContext))
        binding.errorMessage.text = message
        val clicks = mutableMapOf<View, () -> Unit>()

        binding.actionRetry.isVisible = onRetry != null
        onRetry?.let { clicks[binding.actionRetry] = it }

        binding.actionOpenInWebView.isVisible = showOpenInWebView
        onOpenInWebView?.let { clicks[binding.actionOpenInWebView] = it }

        return capture(binding.root, width, clicks, spinner = null)
    }

    /**
     * The progress indicator's colours under the same theme [eu.kanade.tachiyomi.ui.reader.viewer
     * .ReaderProgressIndicator] builds, cover-based colour included, read off a composition since
     * that is the only place the scheme exists.
     */
    suspend fun indicatorColors(): IndicatorColors {
        var colors: IndicatorColors? = null
        val themeCoverBased = Injekt.get<UiPreferences>().themeCoverBased().get()
        val probe = ComposeView(themedContext).apply {
            setContent {
                TachiyomiTheme(seedColor = seedColor?.let { Color(it) }.takeIf { themeCoverBased }) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect {
                        colors = IndicatorColors(scheme.primary.toArgb(), scheme.secondaryContainer.toArgb())
                    }
                }
            }
        }
        attachHost()
        host.addView(probe, FrameLayout.LayoutParams(1, 1))
        try {
            repeat(MAX_COMPOSE_FRAMES) {
                colors?.let { return it }
                awaitFrame()
            }
            return colors ?: error("the theme never composed")
        } finally {
            host.removeView(probe)
        }
    }

    private suspend fun capture(
        view: View,
        width: Int,
        clicks: Map<View, () -> Unit>,
        spinner: View?,
    ): PageSnapshot {
        attachHost()
        host.addView(view, FrameLayout.LayoutParams(width, WRAP_CONTENT))
        try {
            // Attaching composes; a frame lets anything the first composition posted land too.
            awaitFrame()
            view.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            val bitmap = createBitmap(max(1, view.measuredWidth), max(1, view.measuredHeight))
            view.draw(Canvas(bitmap))

            val buttons = clicks.mapNotNull { (button, onClick) ->
                if (!button.isVisible) return@mapNotNull null
                val bounds = boundsIn(view, button)
                var cornerRadius = 0f
                var rippleColor = DEFAULT_RIPPLE
                if (button is MaterialButton) {
                    bounds.top += button.insetTop
                    bounds.bottom -= button.insetBottom
                    cornerRadius = button.shapeAppearanceModel.topLeftCornerSize.getCornerSize(bounds)
                    rippleColor = button.rippleColor
                        ?.getColorForState(PRESSED_STATE, DEFAULT_RIPPLE)
                        ?: DEFAULT_RIPPLE
                }
                PageSnapshot.Button(bounds, cornerRadius, rippleColor, onClick)
            }
            val spinnerCenter = spinner?.let { boundsIn(view, it) }?.let { PointF(it.centerX(), it.centerY()) }

            return PageSnapshot(BitmapTexture(bitmap), bitmap.width, bitmap.height, buttons, spinnerCenter)
                .also { bitmap.recycle() }
        } finally {
            host.removeView(view)
        }
    }

    /** Where [child] sits inside [root], in [root]'s pixels. */
    private fun boundsIn(root: View, child: View): RectF {
        var x = 0f
        var y = 0f
        var current = child
        while (current !== root) {
            x += current.left + current.translationX
            y += current.top + current.translationY
            current = current.parent as? View ?: break
        }
        return RectF(x, y, x + child.width, y + child.height)
    }

    companion object {
        const val INDICATOR_DP = 40

        private const val MAX_COMPOSE_FRAMES = 10

        private val PRESSED_STATE = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)

        private const val DEFAULT_RIPPLE = 0x1F000000
    }
}

/**
 * KMK: Material 3's circular progress indicator, worked out per frame for the renderer to draw -
 * the same motion [tachiyomi.presentation.core.components.CombinedCircularProgressIndicator] shows
 * in the standard viewers. Figures are material3 1.5.0-alpha22's `ProgressIndicator.kt` and
 * `CircularProgressIndicatorTokens`: 40dp across, a 4dp round-capped stroke, a 4dp gap to the track.
 * Angles are degrees clockwise from 3 o'clock, as `drawArc` and the renderer's `arc` take them.
 */
object MaterialSpinner {
    const val DIAMETER_DP = 40f
    const val STROKE_DP = 4f
    private const val GAP_DP = 4f

    private const val CYCLE_MS = 6000L
    private const val HALF_CYCLE_MS = 3000L
    private const val MIN_PROGRESS = 0.1f
    private const val MAX_PROGRESS = 0.87f
    private const val DETERMINATE_ROTATION_MS = 2000L

    /** One arc to stroke. */
    class Arc(val start: Float, val sweep: Float)

    /**
     * The indeterminate indicator at [elapsedMs] into its animation: a 1080 degree turn every six
     * seconds, a further quarter turn every second and a half, and the arc growing from 10% to 87%
     * and back. Its track is transparent, so only the indicator is drawn.
     */
    fun indeterminate(elapsedMs: Long): Arc {
        val t = elapsedMs % CYCLE_MS
        val rotation = t * 1080f / CYCLE_MS + additionalRotation(t)
        val progress = if (t <= HALF_CYCLE_MS) {
            lerp(MIN_PROGRESS, MAX_PROGRESS, t.toFloat() / HALF_CYCLE_MS)
        } else {
            lerp(MAX_PROGRESS, MIN_PROGRESS, standardEasing((t - HALF_CYCLE_MS).toFloat() / HALF_CYCLE_MS))
        }
        return Arc(rotation, progress * 360f)
    }

    /**
     * The determinate indicator at [progress], turning once every two seconds as the combined
     * indicator turns it: the indicator from 12 o'clock, and the track in what is left of the ring
     * minus a gap either side.
     */
    fun determinate(elapsedMs: Long, progress: Float): Pair<Arc, Arc> {
        val rotation = (elapsedMs % DETERMINATE_ROTATION_MS) * 360f / DETERMINATE_ROTATION_MS
        val sweep = progress.coerceIn(0f, 1f) * 360f
        // Round caps reach half a stroke past each end, so the gap is widened by a whole stroke.
        val gapSweep = (GAP_DP + STROKE_DP) / (Math.PI.toFloat() * DIAMETER_DP) * 360f
        val start = 270f + rotation
        val indicator = Arc(start, sweep)
        val track = Arc(start + sweep + min(sweep, gapSweep), 360f - sweep - min(sweep, gapSweep) * 2)
        return indicator to track
    }

    /** Quarter turns at 0.3s, 1.8s, 3.3s and 4.8s, 300ms each, linear - the keyframes' own easing. */
    private fun additionalRotation(t: Long): Float {
        var turned = 0f
        for (step in 0 until 4) {
            val begin = step * 1500L
            if (t < begin) break
            turned = step * 90f + 90f * ((t - begin).toFloat() / 300f).coerceAtMost(1f)
        }
        return turned
    }

    /** Material's standard easing, `CubicBezierEasing(0.2, 0, 0, 1)`. */
    private fun standardEasing(fraction: Float): Float = cubicBezier(0.2f, 0f, 0f, 1f, fraction)

    private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
}

/**
 * KMK: the framework ripple a Material button shows when pressed, worked out per frame - the same
 * figures as `android.graphics.drawable.RippleForeground`: the circle grows from 30% of the longer
 * side to the half diagonal while its centre drifts from the touch to the middle, over 225ms on a
 * decelerate curve; it fades in over 75ms, and on release fades out over 150ms, held until at least
 * 225ms have passed since the press.
 */
object ButtonRipple {
    private const val ENTER_MS = 225f
    private const val OPACITY_ENTER_MS = 75f
    private const val OPACITY_EXIT_MS = 150f
    private const val OPACITY_HOLD_MS = 225L

    class Frame(val x: Float, val y: Float, val radius: Float, val opacity: Float)

    /**
     * The ripple over [bounds], pressed at [touchX]/[touchY] [sinceDownMs] ago and released
     * [sinceUpMs] ago (null while held). Null once it has faded out.
     */
    fun frame(bounds: RectF, touchX: Float, touchY: Float, sinceDownMs: Long, sinceUpMs: Long?): Frame? {
        val tween = decelerate((sinceDownMs / ENTER_MS).coerceIn(0f, 1f))
        val startRadius = max(bounds.width(), bounds.height()) * 0.3f
        val halfW = bounds.width() / 2f
        val halfH = bounds.height() / 2f
        val targetRadius = sqrt(halfW * halfW + halfH * halfH)

        var opacity = (sinceDownMs / OPACITY_ENTER_MS).coerceIn(0f, 1f)
        if (sinceUpMs != null) {
            val sinceFadeStart = min(sinceUpMs, sinceDownMs - OPACITY_HOLD_MS)
            if (sinceFadeStart > 0) {
                opacity *= 1f - (sinceFadeStart / OPACITY_EXIT_MS).coerceIn(0f, 1f)
                if (opacity <= 0f) return null
            }
        }

        return Frame(
            x = touchX + (bounds.centerX() - touchX) * tween,
            y = touchY + (bounds.centerY() - touchY) * tween,
            radius = startRadius + (targetRadius - startRadius) * tween,
            opacity = opacity,
        )
    }

    /** `PathInterpolator(0.4, 0, 0.2, 1)`. */
    private fun decelerate(fraction: Float): Float = cubicBezier(0.4f, 0f, 0.2f, 1f, fraction)
}

/** The y of a cubic bezier easing curve through (0, 0) and (1, 1) at x = [fraction]. */
private fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, fraction: Float): Float {
    if (fraction <= 0f) return 0f
    if (fraction >= 1f) return 1f
    fun bezier(a: Float, b: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }
    // x is monotonic in t on [0, 1], so bisection always finds it.
    var low = 0f
    var high = 1f
    var t = fraction
    repeat(24) {
        val x = bezier(x1, x2, t)
        if (x < fraction) low = t else high = t
        t = (low + high) / 2f
    }
    return bezier(y1, y2, t)
}
