package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/**
 * KMK: gamepad zoom and panning for a reader viewer, after J2K's controller support.
 *
 * X/Y zoom out/in (a tap is one step, holding keeps going), L2/R2 and the right stick's Y axis zoom
 * while held, and the left stick pans a zoomed page - or, in a strip, always scrolls vertically.
 * Page turns stay with the viewer: an un-zoomed left stick and the hat switch are left unhandled, so
 * Android turns them into the D-pad key events the viewer already reads.
 *
 * Axes only report a change, so held input is applied once per display frame from the last values
 * until it returns to rest.
 */
class GamepadInput(
    private val scope: CoroutineScope,
    private val target: Target,
    private val isMenuVisible: () -> Boolean,
) {

    interface Target {
        /** Whether the page is zoomed past its resting scale, so a stick pans instead of turning. */
        fun isZoomedIn(): Boolean

        /** Zooms one animated step in or out. */
        fun zoomStep(zoomIn: Boolean)

        /** Zooms by [factor] about the centre of the screen at once; called every frame while held. */
        fun zoomBy(factor: Float)

        /**
         * Pans by fractions of the screen; positive [dx]/[dy] bring more of the right/bottom into view.
         * Only called while [isZoomedIn].
         */
        fun panBy(dx: Float, dy: Float)

        /** A strip scrolls with the stick's vertical axis instead of panning with it. */
        val isStrip: Boolean get() = false

        /** Scrolls a strip by a fraction of the screen height, positive downwards. */
        fun scrollBy(dy: Float) {}
    }

    private var stickX = 0f
    private var stickY = 0f
    private var axisZoomRate = 0f
    private var buttonZoomRate = 0f
    private var loop: Job? = null

    /** X/Y; returns true when the event was consumed. Other keys are the viewer's. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val zoomIn = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_Y -> true
            KeyEvent.KEYCODE_BUTTON_X -> false
            else -> return false
        }
        if (isMenuVisible()) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                target.zoomStep(zoomIn)
            } else {
                buttonZoomRate = if (zoomIn) 1f else -1f
                startLoop()
            }
            KeyEvent.ACTION_UP -> buttonZoomRate = 0f
        }
        return true
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) {
            return false
        }
        // The hat switch is the D-pad on most controllers; consuming it would stop Android turning
        // it into the D-pad key events page turns rely on.
        if (event.getAxisValue(MotionEvent.AXIS_HAT_X) != 0f || event.getAxisValue(MotionEvent.AXIS_HAT_Y) != 0f) {
            return false
        }

        stickX = event.getAxisValue(MotionEvent.AXIS_X).deadzone(STICK_DEADZONE)
        stickY = event.getAxisValue(MotionEvent.AXIS_Y).deadzone(STICK_DEADZONE)

        val leftTrigger = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rightTrigger = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        val triggerRate = (rightTrigger - leftTrigger).deadzone(TRIGGER_DEADZONE)
        // Pushed up zooms in; up is negative.
        val rightStickRate = -event.getAxisValue(MotionEvent.AXIS_RZ).deadzone(STICK_DEADZONE)
        axisZoomRate = (triggerRate + rightStickRate).coerceIn(-1f, 1f)

        if (isMenuVisible()) return false
        val handled = axisZoomRate != 0f || stickMoves()
        if (handled) startLoop()
        return handled
    }

    private fun stickMoves(): Boolean {
        if (stickX == 0f && stickY == 0f) return false
        return target.isZoomedIn() || (stickY != 0f && target.isStrip)
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var last = awaitFrame()
            while (isActive) {
                val now = awaitFrame()
                val seconds = ((now - last) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
                last = now
                if (isMenuVisible() || !step(seconds)) break
            }
        }
    }

    /** One frame of held input; false once everything is at rest. */
    private fun step(seconds: Float): Boolean {
        var active = false
        val zoomRate = (axisZoomRate + buttonZoomRate).coerceIn(-1f, 1f)
        if (zoomRate != 0f) {
            target.zoomBy(ZOOM_PER_SECOND.pow(zoomRate * seconds))
            active = true
        }
        if (stickX != 0f || stickY != 0f) {
            val zoomed = target.isZoomedIn()
            // A strip scrolls with the vertical axis whatever the zoom; the horizontal one pans.
            val scrolled = stickY != 0f && target.isStrip
            if (scrolled) target.scrollBy(stickY * SCROLL_PER_SECOND * seconds)
            if (zoomed) {
                target.panBy(stickX * PAN_PER_SECOND * seconds, if (scrolled) 0f else stickY * PAN_PER_SECOND * seconds)
            }
            active = active || zoomed || scrolled
        }
        return active
    }

    private fun Float.deadzone(zone: Float) = if (abs(this) > zone) this else 0f

    private companion object {
        const val STICK_DEADZONE = 0.2f
        const val TRIGGER_DEADZONE = 0.1f

        /** Scale multiplier per second at full deflection. */
        const val ZOOM_PER_SECOND = 3f

        /** Screens per second at full deflection. */
        const val PAN_PER_SECOND = 1.5f
        const val SCROLL_PER_SECOND = 2f

        /** A frame skipped longer than this (a GC, a dropped vsync) is not made up in one jump. */
        const val MAX_FRAME_SECONDS = 0.05f
    }
}

/** KMK: the keys a viewer gives up to focus navigation while the reader menu is open. */
val DPAD_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)
