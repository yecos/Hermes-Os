package com.hermes.desktop

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DesktopShellView(
    context: Context,
    private val apps: List<AppEntry>,
    private val onLaunchApp: (AppEntry) -> Unit,
    private val onOpenTermux: () -> Unit,
    private val onStartHermes: () -> Unit
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val clock = TextView(context)
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 30_000)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(12))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(12, 18, 30), Color.rgb(22, 30, 47), Color.rgb(8, 12, 20))
        )

        addView(topBar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(58)))
        addView(appArea(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(taskbar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(70)))
        handler.post(clockTick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clockTick)
        super.onDetachedFromWindow()
    }

    private fun topBar(): View {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)

            addView(TextView(context).apply {
                text = "HERMES DESKTOP"
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(context).apply {
                text = "Android · Linux · IA"
                setTextColor(Color.rgb(164, 174, 194))
                textSize = 13f
            })
        }
    }

    private fun appArea(): View {
        val grid = GridLayout(context).apply {
            columnCount = 6
            useDefaultMargins = true
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(dp(8), dp(16), dp(8), dp(24))
        }

        apps.take(36).forEach { app ->
            val tile = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(12))
                isClickable = true
                isFocusable = true
                background = rounded(Color.argb(105, 255, 255, 255), dp(18))
                setOnClickListener { onLaunchApp(app) }
            }

            tile.addView(ImageView(context).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LayoutParams(dp(52), dp(52)))

            tile.addView(TextView(context).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 2
            }, LayoutParams(dp(130), dp(48)))

            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = dp(145)
                height = dp(130)
            })
        }

        return ScrollView(context).apply {
            isFillViewport = true
            addView(grid)
        }
    }

    private fun taskbar(): View {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.argb(180, 20, 27, 40), dp(22))

            addView(taskButton("🤖 Hermes") { onStartHermes() })
            addView(taskButton(">_ Termux") { onOpenTermux() })

            addView(TextView(context).apply {
                text = apps.size.toString() + " apps"
                setTextColor(Color.rgb(160, 170, 190))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            clock.setTextColor(Color.WHITE)
            clock.textSize = 17f
            clock.typeface = Typeface.DEFAULT_BOLD
            clock.gravity = Gravity.CENTER
            addView(clock, LayoutParams(dp(85), LayoutParams.MATCH_PARENT))
        }
    }

    private fun taskButton(label: String, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(Color.argb(90, 255, 255, 255), dp(14))
            setOnClickListener { action() }
        }
    }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
