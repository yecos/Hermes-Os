package com.hermes.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class TouchpadView(
    context: Context,
    private val onNavigate: (dx: Int, dy: Int) -> Unit,
    private val onTap: () -> Unit
) : View(context) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 27, 40)
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(72, 89, 118)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(17f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val helper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(157, 170, 194)
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false
    private val step = dp(36f)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(22f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, bg)
        canvas.drawRoundRect(
            dp(1f),
            dp(1f),
            width.toFloat() - dp(1f),
            height.toFloat() - dp(1f),
            radius,
            radius,
            border
        )
        canvas.drawText("TOUCHPAD", width / 2f, height / 2f - dp(8f), title)
        canvas.drawText("desliza para navegar · toca para abrir", width / 2f, height / 2f + dp(18f), helper)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downAt = System.currentTimeMillis()
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                if (abs(dx) >= step || abs(dy) >= step) {
                    if (abs(dx) > abs(dy)) {
                        onNavigate(if (dx > 0) 1 else -1, 0)
                    } else {
                        onNavigate(0, if (dy > 0) 1 else -1)
                    }
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    lastX = event.x
                    lastY = event.y
                    moved = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - downAt
                val total = abs(event.x - downX) + abs(event.y - downY)
                if (!moved && elapsed < 350 && total < step) {
                    onTap()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
