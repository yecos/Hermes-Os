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

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 21, 32)
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(104, 213, 255)
    }
    private val accentSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 104, 213, 255)
    }
    private val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(241, 245, 251)
        textSize = dp(16f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val secondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(142, 158, 184)
        textSize = dp(11.5f)
        textAlign = Paint.Align.CENTER
    }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 104, 213, 255)
    }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(132, 222, 255)
        textSize = dp(9f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 225, 247, 255)
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false
    private var dragging = false
    private var twoFinger = false
    private var touching = false

    private val longPress = Runnable {
        if (!moved && !twoFinger && privileged()) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onDragStart()
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val radius = dp(28f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, panel)
        canvas.drawRoundRect(
            dp(1f), dp(1f),
            width.toFloat() - dp(1f),
            height.toFloat() - dp(1f),
            radius, radius, inner
        )

        if (privileged()) {
            canvas.drawCircle(width * .5f, height * .42f, width * .18f, accentSoft)
        }

        val chipWidth = dp(92f)
        val chipHeight = dp(26f)
        val chipLeft = width / 2f - chipWidth / 2f
        val chipTop = dp(20f)
        canvas.drawRoundRect(
            chipLeft, chipTop,
            chipLeft + chipWidth, chipTop + chipHeight,
            dp(13f), dp(13f), chip
        )
        canvas.drawCircle(chipLeft + dp(14f), chipTop + chipHeight / 2f, dp(3.5f), accent)
        canvas.drawText(
            if (privileged()) "MOUSE LIVE" else "LAUNCHER",
            width / 2f + dp(7f),
            chipTop + dp(17.5f),
            chipText
        )

        canvas.drawText(
            when {
                dragging -> "Arrastrando"
                twoFinger -> "Scroll"
                touching -> "Controlando"
                else -> "Touchpad"
            },
            width / 2f,
            height / 2f - dp(8f),
            primary
        )

        canvas.drawText(
            if (privileged())
                "1 dedo mueve · tap clic · 2 dedos scroll · mantener arrastra"
            else
                "Desliza para navegar · toca para abrir",
            width / 2f,
            height / 2f + dp(20f),
            secondary
        )

        if (touching && !twoFinger) {
            finger.setShadowLayer(dp(14f), 0f, 0f, Color.argb(160, 104, 213, 255))
            canvas.drawCircle(lastX, lastY, if (dragging) dp(9f) else dp(6f), finger)
            finger.clearShadowLayer()
        }

        val gestureY = height - dp(34f)
        drawGestureHint(canvas, width * .25f, gestureY, "TAP", "clic")
        drawGestureHint(canvas, width * .50f, gestureY, "2F", "scroll")
        drawGestureHint(canvas, width * .75f, gestureY, "HOLD", "drag")
    }

    private fun drawGestureHint(canvas: Canvas, x: Float, y: Float, key: String, label: String) {
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(28, 255, 255, 255)
        }
        val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 193, 214)
            textSize = dp(8f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(108, 122, 145)
            textSize = dp(8f)
            textAlign = Paint.Align.CENTER
        }

        canvas.drawRoundRect(
            x - dp(22f), y - dp(11f),
            x + dp(22f), y + dp(7f),
            dp(9f), dp(9f), badge
        )
        canvas.drawText(key, x, y + dp(2f), keyPaint)
        canvas.drawText(label, x, y + dp(20f), labelPaint)
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
                touching = true
                handler.postDelayed(longPress, 430)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFinger = true
                touching = true
                handler.removeCallbacks(longPress)
                lastX = event.x
                lastY = event.y
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                if (event.pointerCount >= 2 || twoFinger) {
                    twoFinger = true
                    if (abs(dy) > dp(4f)) {
                        onScroll(dy)
                        lastY = event.y
                        moved = true
                        invalidate()
                    }
                    return true
                }

                if (abs(dx) + abs(dy) > dp(2.5f)) {
                    if (!dragging) handler.removeCallbacks(longPress)
                    onMove(dx, dy, dragging)
                    lastX = event.x
                    lastY = event.y
                    moved = true
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                twoFinger = event.pointerCount - 1 >= 2
                lastX = event.x
                lastY = event.y
                invalidate()
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

                touching = false
                twoFinger = false
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

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
