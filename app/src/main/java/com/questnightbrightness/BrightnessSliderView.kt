package com.questnightbrightness

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

/**
 * Density-independent slider used instead of the platform SeekBar.
 *
 * Horizon OS can stretch a SeekBar's LayerDrawable to the widget bounds, ignoring
 * the track's intended intrinsic height. Drawing both parts here guarantees the
 * same 28dp thumb / 8dp track ratio on Quest and standard Android displays.
 */
class BrightnessSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val trackHeightPx = resources.getDimension(R.dimen.brightness_slider_track_height)
    private val thumbRadiusPx = resources.getDimension(R.dimen.brightness_slider_thumb_diameter) / 2f
    private val desiredHeightPx = resources.getDimensionPixelSize(R.dimen.brightness_slider_touch_height)
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.track)
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBounds = RectF()

    private var minimum = 1
    private var maximum = 45
    var value: Int = minimum
        private set

    var onValueChanged: ((value: Int, fromUser: Boolean) -> Unit)? = null
    var onStartTracking: (() -> Unit)? = null
    var onStopTracking: (() -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setRange(minimum: Int, maximum: Int) {
        require(minimum < maximum)
        this.minimum = minimum
        this.maximum = maximum
        setValue(value)
    }

    fun setValue(newValue: Int, fromUser: Boolean = false) {
        val constrained = newValue.coerceIn(minimum, maximum)
        if (constrained == value) return
        value = constrained
        invalidate()
        onValueChanged?.invoke(value, fromUser)
        if (fromUser) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = resources.getDimensionPixelSize(R.dimen.brightness_slider_min_width) +
            paddingLeft + paddingRight
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeightPx + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2f
        val left = paddingLeft + thumbRadiusPx
        val right = width - paddingRight - thumbRadiusPx
        if (right <= left) return

        val trackRadius = trackHeightPx / 2f
        trackBounds.set(left, centerY - trackRadius, right, centerY + trackRadius)
        canvas.drawRoundRect(trackBounds, trackRadius, trackRadius, inactivePaint)

        val thumbCenterX = valueToX(value, left, right)
        trackBounds.right = thumbCenterX
        if (trackBounds.width() > 0f) {
            canvas.drawRoundRect(trackBounds, trackRadius, trackRadius, activePaint)
        }

        thumbPaint.color = context.getColor(if (isPressed) R.color.accent_pressed else R.color.accent)
        canvas.drawCircle(thumbCenterX, centerY, thumbRadiusPx, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                onStartTracking?.invoke()
                updateFromTouch(event.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x)
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onStopTracking?.invoke()
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onStopTracking?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        setValue(value + delta, fromUser = true)
        return true
    }

    @Suppress("DEPRECATION") // API 36 deprecates the only public RangeInfo factory.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            minimum.toFloat(),
            maximum.toFloat(),
            value.toFloat()
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id && arguments != null) {
            val requested = arguments.getFloat(
                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                value.toFloat()
            ).roundToInt()
            setValue(requested, fromUser = true)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun updateFromTouch(touchX: Float) {
        val left = paddingLeft + thumbRadiusPx
        val right = width - paddingRight - thumbRadiusPx
        if (right <= left) return
        val fraction = ((touchX - left) / (right - left)).coerceIn(0f, 1f)
        val newValue = (minimum + fraction * (maximum - minimum)).roundToInt()
        setValue(newValue, fromUser = true)
    }

    private fun valueToX(value: Int, left: Float, right: Float): Float {
        val fraction = (value - minimum).toFloat() / (maximum - minimum).toFloat()
        return left + fraction * (right - left)
    }
}
