package com.hermes.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class TouchpadView(
    context: Context,
    private val onMove: (dx: Float, dy: Float, dragging: Boolean) -> Unit,
    private val onTap: () -> Unit,
    private val onScroll: (dy: Float) -> Unit,
    private val onDragStart: () -> Unit,
    private val onDragEnd: () -> Unit,
    private val privileged: () -> Boolean
) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
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
    private var dragging = false
    private var twoFinger = false

    private val longPress = Runnable {
        if (!moved && !twoFinger && privileged()) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onDragStart()
        }
    }

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

        canvas.drawText(
            if (privileged()) "TOUCHPAD · MOUSE REAL" else "TOUCHPAD · LAUNCHER",
            width / 2f,
            height / 2f - dp(9f),
            title
        )
        canvas.drawText(
            if (privileged())
                "1 dedo: mouse · 2 dedos: scroll · mantener: arrastrar"
            else
                "desliza para navegar · toca para abrir",
            width / 2f,
            height / 2f + dp(18f),
            helper
        )
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
                dragging = false
                twoFinger = false
                handler.postDelayed(longPress, 430)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFinger = true
                handler.removeCallbacks(longPress)
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                if (event.pointerCount >= 2 || twoFinger) {
                    twoFinger = true
                    if (abs(dy) > dp(5f)) {
                        onScroll(dy)
                        lastY = event.y
                        moved = true
                    }
                    return true
                }

                if (abs(dx) + abs(dy) > dp(3f)) {
                    if (!dragging) handler.removeCallbacks(longPress)
                    onMove(dx * 1.65f, dy * 1.65f, dragging)
                    lastX = event.x
                    lastY = event.y
                    moved = true
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                twoFinger = event.pointerCount - 1 >= 2
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)

                if (dragging) {
                    onDragEnd()
                    dragging = false
                } else {
                    val elapsed = System.currentTimeMillis() - downAt
                    val total = abs(event.x - downX) + abs(event.y - downY)
                    if (!twoFinger && !moved && elapsed < 350 && total < dp(18f)) {
                        onTap()
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        performClick()
                    }
                }

                twoFinger = false
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
