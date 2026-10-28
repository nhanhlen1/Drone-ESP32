package dev.drone.rcbench.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal object Palette {
    val background = Color.rgb(8, 15, 27)
    val card = Color.rgb(18, 29, 44)
    val line = Color.rgb(40, 59, 77)
    val mint = Color.rgb(99, 231, 195)
    val text = Color.rgb(234, 242, 249)
    val muted = Color.rgb(142, 162, 180)
    val amber = Color.rgb(255, 204, 112)
    val red = Color.rgb(251, 112, 123)
}

/** Each View captures one pointer; sibling Views can retain other pointers. */
class StickView @JvmOverloads constructor(context: Context, private val throttleStick: Boolean = false) : View(context) {
    var onValue: ((x: Float, y: Float) -> Unit)? = null
    var expo: Float = .25f
    var deadZone: Float = .06f
    private var activePointer = -1
    private var dragStartY = 0f
    private var throttleAtDown = 0f
    private var x = 0f
    private var y = if (throttleStick) -1f else 0f
    private var latchedThrottle = 0f
    private var disposed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        contentDescription = if (throttleStick) "Joystick trái: throttle và yaw" else "Joystick phải: pitch và roll"
        minimumHeight = dp(180)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun curve(value: Float): Float {
        if (!value.isFinite() || abs(value) <= deadZone) return 0f
        val normalized = ((abs(value).coerceAtMost(1f) - deadZone) / (1f - deadZone)).coerceIn(0f, 1f) * if (value < 0f) -1f else 1f
        return (1f - expo) * normalized + expo * normalized.pow(3)
    }
    private fun publish() {
        if (disposed || !isEnabled) return
        onValue?.invoke(curve(x), if (throttleStick) latchedThrottle else curve(y))
    }
    fun reset(resetThrottle: Boolean = false, notify: Boolean = false) {
        activePointer = -1
        x = 0f
        if (resetThrottle) latchedThrottle = 0f
        y = if (throttleStick) latchedThrottle * 2 - 1 else 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        if (notify) publish()
        invalidate()
    }
    fun dispose() {
        disposed = true
        reset()
        onValue = null
    }
    override fun setEnabled(enabled: Boolean) {
        if (!enabled) reset()
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else .45f
    }
    override fun onDetachedFromWindow() {
        // A MotionEvent cancellation can arrive after detachment; forget capture.
        activePointer = -1
        super.onDetachedFromWindow()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || disposed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                dragStartY = event.y
                throttleAtDown = latchedThrottle
                parent?.requestDisallowInterceptTouchEvent(true)
                move(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) move(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePointer >= 0) {
                    reset(notify = true)
                    performClick()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == activePointer) reset(notify = true)
        }
        return true
    }
    private fun move(px: Float, py: Float) {
        val radius = min(width, height) * .38f
        if (radius <= 0) return
        x = ((px - width / 2f) / radius).coerceIn(-1f, 1f)
        if (throttleStick) {
            // Touch location never jumps throttle: vertical movement is relative.
            latchedThrottle = (throttleAtDown - (py - dragStartY) / (2 * radius)).coerceIn(0f, 1f)
            y = latchedThrottle * 2 - 1
        } else {
            y = (-(py - height / 2f) / radius).coerceIn(-1f, 1f)
            val length = sqrt(x * x + y * y)
            if (length > 1) { x /= length; y /= length }
        }
        publish()
        invalidate()
    }
    override fun performClick(): Boolean = super.performClick()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val radius = min(width, height) * .38f
        paint.style = Paint.Style.FILL; paint.color = Palette.card
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = Palette.line
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawCircle(cx, cy, radius * .4f, paint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)
        val kx = cx + x * radius; val ky = cy - y * radius
        paint.color = Palette.mint; paint.strokeWidth = dp(2).toFloat()
        canvas.drawLine(cx, cy, kx, ky, paint)
        paint.style = Paint.Style.FILL; paint.alpha = 48
        canvas.drawCircle(kx, ky, radius * .24f, paint)
        paint.alpha = 255
        canvas.drawCircle(kx, ky, radius * .14f, paint)
    }
}

/** Independent momentary contact: release, focus loss or leaving its bounds stops RC. */
class DeadmanView(context: Context) : View(context) {
    var onHeld: ((Boolean) -> Unit)? = null
    private var pointer = -1
    private var held = false
    private var disposed = false
    private val handler = Handler(Looper.getMainLooper())
    private val holdPulse = object : Runnable {
        override fun run() {
            if (!held || disposed || !isEnabled || !isAttachedToWindow) return
            onHeld?.invoke(true)
            handler.postDelayed(this, 50)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundBounds = RectF()
    init { isClickable = true; minimumHeight = dp(60); contentDescription = "Giữ nút này để truyền RC. Thả tay dừng ngay." }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun setHeld(next: Boolean) {
        if (held == next) return
        held = next
        handler.removeCallbacks(holdPulse)
        if (!disposed) onHeld?.invoke(next)
        if (next) handler.postDelayed(holdPulse, 50)
        invalidate()
    }
    fun release() {
        pointer = -1
        setHeld(false)
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    fun dispose() { release(); disposed = true; onHeld = null }
    override fun setEnabled(enabled: Boolean) {
        if (!enabled) release()
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else .45f
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) release()
        super.onWindowFocusChanged(hasWindowFocus)
    }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || disposed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
                setHeld(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0 || event.getX(index) !in 0f..width.toFloat() || event.getY(index) !in 0f..height.toFloat()) release()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { release(); performClick() }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) release()
        }
        return true
    }
    override fun performClick(): Boolean = super.performClick()
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundBounds.set(0f, 0f, w.toFloat(), h.toFloat())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = if (held) Palette.mint else Palette.card
        canvas.drawRoundRect(backgroundBounds, dp(14).toFloat(), dp(14).toFloat(), paint)
        paint.color = if (held) Palette.background else Palette.mint
        paint.textSize = dp(14).toFloat(); paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
        canvas.drawText(if (held) "ĐANG GIỮ • RC 20 Hz" else "GIỮ ĐỂ TRUYỀN RC", width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
