package ru.evrasia.research

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

internal class AccentColorPickerView(context: Context) : View(context) {
    var onColorChanged: ((Int) -> Unit)? = null
    var onColorCommitted: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private val valueRect = RectF()
    private val hueRect = RectF()
    private val hsv = floatArrayOf(185f, 1f, 0.75f)
    private var activeArea = AREA_NONE

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }

    fun selectedColor(): Int = Color.HSVToColor(hsv)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(284)
        setMeasuredDimension(
            resolveSize(dp(280), widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateRects()

        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.shader = LinearGradient(
            valueRect.left,
            valueRect.top,
            valueRect.right,
            valueRect.top,
            Color.WHITE,
            hueColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(valueRect, dp(12).toFloat(), dp(12).toFloat(), paint)

        paint.shader = LinearGradient(
            valueRect.left,
            valueRect.top,
            valueRect.left,
            valueRect.bottom,
            Color.TRANSPARENT,
            Color.BLACK,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(valueRect, dp(12).toFloat(), dp(12).toFloat(), paint)

        paint.shader = LinearGradient(
            hueRect.left,
            hueRect.top,
            hueRect.right,
            hueRect.top,
            intArrayOf(
                Color.RED,
                Color.YELLOW,
                Color.GREEN,
                Color.CYAN,
                Color.BLUE,
                Color.MAGENTA,
                Color.RED
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(hueRect, dp(10).toFloat(), dp(10).toFloat(), paint)
        paint.shader = null

        val valueX = valueRect.left + hsv[1] * valueRect.width()
        val valueY = valueRect.top + (1f - hsv[2]) * valueRect.height()
        selectorPaint.color = if (hsv[2] > 0.62f && hsv[1] < 0.45f) Color.BLACK else Color.WHITE
        selectorPaint.strokeWidth = dp(3).toFloat()
        canvas.drawCircle(valueX, valueY, dp(9).toFloat(), selectorPaint)
        selectorPaint.color = if (selectorPaint.color == Color.WHITE) Color.BLACK else Color.WHITE
        selectorPaint.strokeWidth = dp(1).toFloat()
        canvas.drawCircle(valueX, valueY, dp(11).toFloat(), selectorPaint)

        val hueX = hueRect.left + (hsv[0] / 360f) * hueRect.width()
        selectorPaint.color = Color.WHITE
        selectorPaint.strokeWidth = dp(3).toFloat()
        canvas.drawCircle(hueX, hueRect.centerY(), dp(9).toFloat(), selectorPaint)
        selectorPaint.color = Color.BLACK
        selectorPaint.strokeWidth = dp(1).toFloat()
        canvas.drawCircle(hueX, hueRect.centerY(), dp(11).toFloat(), selectorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updateRects()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeArea = when {
                    hueRect.contains(event.x, event.y) -> AREA_HUE
                    valueRect.contains(event.x, event.y) -> AREA_VALUE
                    else -> AREA_NONE
                }
                if (activeArea == AREA_NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeArea == AREA_NONE) return false
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (activeArea == AREA_NONE) return false
                updateFromTouch(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                activeArea = AREA_NONE
                onColorCommitted?.invoke(selectedColor())
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                activeArea = AREA_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float, y: Float) {
        when (activeArea) {
            AREA_HUE -> {
                val fraction = ((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f)
                hsv[0] = if (fraction >= 1f) 359.999f else fraction * 360f
            }
            AREA_VALUE -> {
                hsv[1] = ((x - valueRect.left) / valueRect.width()).coerceIn(0f, 1f)
                hsv[2] = (1f - ((y - valueRect.top) / valueRect.height())).coerceIn(0f, 1f)
            }
        }
        invalidate()
        onColorChanged?.invoke(selectedColor())
    }

    private fun updateRects() {
        val side = dp(12).toFloat()
        val gap = dp(18).toFloat()
        val hueHeight = dp(28).toFloat()
        val bottom = height - dp(10).toFloat()
        hueRect.set(side, bottom - hueHeight, width - side, bottom)
        valueRect.set(side, dp(8).toFloat(), width - side, hueRect.top - gap)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val AREA_NONE = 0
        private const val AREA_VALUE = 1
        private const val AREA_HUE = 2
    }
}
