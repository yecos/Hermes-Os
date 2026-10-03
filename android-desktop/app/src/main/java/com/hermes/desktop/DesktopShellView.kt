package com.hermes.desktop

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
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
    initialMode: DesktopMode,
    private val onLaunchApp: (AppEntry) -> Unit,
    private val onOpenTermux: () -> Unit,
    private val onStartHermes: () -> Unit
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val clock = TextView(context)
    private val status = TextView(context)
    private val grid = GridLayout(context)
    private val scroll = ScrollView(context)
    private val recentRow = LinearLayout(context)

    private var mode = initialMode
    private var filteredApps = apps
    private var selectedIndex = 0
    private val appTiles = mutableListOf<View>()
    private val recents = mutableListOf<AppEntry>()

    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            status.text = deviceStatus()
            handler.postDelayed(this, 30_000)
        }
    }

    init {
        orientation = VERTICAL
        isFocusableInTouchMode = true
        setPadding(dp(18), dp(14), dp(18), dp(12))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(12, 18, 30), Color.rgb(22, 30, 47), Color.rgb(8, 12, 20))
        )

        addView(topBar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(58)))
        addView(appArea(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(taskbar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(72)))

        renderApps()
        renderRecents()
        handler.post(clockTick)
        post { requestFocus() }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clockTick)
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { navigate(-1, 0); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { navigate(1, 0); true }
            KeyEvent.KEYCODE_DPAD_UP -> { navigate(0, -1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { navigate(0, 1); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> { activateSelection(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    fun navigate(dx: Int, dy: Int) {
        if (filteredApps.isEmpty()) return
        val columns = columns()
        val row = selectedIndex / columns
        val col = selectedIndex % columns
        val maxRow = (filteredApps.lastIndex / columns).coerceAtLeast(0)

        val targetRow = (row + dy).coerceIn(0, maxRow)
        val targetCol = (col + dx).coerceIn(0, columns - 1)
        selectedIndex = (targetRow * columns + targetCol).coerceAtMost(filteredApps.lastIndex)
        refreshSelection()
    }

    fun activateSelection() {
        val app = filteredApps.getOrNull(selectedIndex) ?: return
        launch(app)
    }

    fun setSearchQuery(query: String) {
        filteredApps = if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        selectedIndex = 0
        renderApps()
    }

    fun setMode(newMode: DesktopMode) {
        if (mode == newMode) return
        mode = newMode
        selectedIndex = 0
        renderApps()
    }

    fun cycleRecent() {
        val app = recents.firstOrNull() ?: return
        launch(app)
    }

    private fun topBar(): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)

            addView(TextView(context).apply {
                text = "HERMES DESKTOP"
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            status.setTextColor(Color.rgb(164, 174, 194))
            status.textSize = 13f
            status.gravity = Gravity.END
            addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

    private fun appArea(): View {
        grid.useDefaultMargins = true
        grid.alignmentMode = GridLayout.ALIGN_BOUNDS
        grid.setPadding(dp(8), dp(16), dp(8), dp(24))

        scroll.isFillViewport = true
        scroll.addView(grid)
        return scroll
    }

    private fun renderApps() {
        grid.removeAllViews()
        appTiles.clear()
        grid.columnCount = columns()

        filteredApps.take(if (mode == DesktopMode.TV) 30 else 42).forEachIndexed { index, app ->
            val tile = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = app.label
                setOnClickListener {
                    selectedIndex = index
                    refreshSelection()
                    launch(app)
                }
            }

            val iconSize = if (mode == DesktopMode.TV) dp(68) else dp(52)
            tile.addView(ImageView(context).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LayoutParams(iconSize, iconSize))

            tile.addView(TextView(context).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = if (mode == DesktopMode.TV) 15f else 12f
                gravity = Gravity.CENTER
                maxLines = 2
            }, LayoutParams(
                if (mode == DesktopMode.TV) dp(180) else dp(130),
                if (mode == DesktopMode.TV) dp(56) else dp(48)
            ))

            val lp = GridLayout.LayoutParams().apply {
                width = if (mode == DesktopMode.TV) dp(200) else dp(145)
                height = if (mode == DesktopMode.TV) dp(160) else dp(130)
            }
            grid.addView(tile, lp)
            appTiles += tile
        }
        selectedIndex = selectedIndex.coerceAtMost((appTiles.size - 1).coerceAtLeast(0))
        refreshSelection()
    }

    private fun refreshSelection() {
        appTiles.forEachIndexed { index, tile ->
            tile.background = rounded(
                if (index == selectedIndex)
                    Color.argb(190, 54, 105, 190)
                else
                    Color.argb(90, 255, 255, 255),
                dp(18),
                if (index == selectedIndex) Color.rgb(143, 190, 255) else null
            )
        }

        val selected = appTiles.getOrNull(selectedIndex) ?: return
        scroll.post {
            val target = (selected.top - dp(80)).coerceAtLeast(0)
            scroll.smoothScrollTo(0, target)
        }
    }

    private fun launch(app: AppEntry) {
        recents.removeAll { it.packageName == app.packageName }
        recents.add(0, app)
        while (recents.size > 4) recents.removeAt(recents.lastIndex)
        renderRecents()
        onLaunchApp(app)
    }

    private fun taskbar(): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.argb(180, 20, 27, 40), dp(22), null)

            addView(taskButton("🤖 Hermes") { onStartHermes() })
            addView(taskButton(">_ Termux") { onOpenTermux() })

            recentRow.orientation = HORIZONTAL
            recentRow.gravity = Gravity.CENTER_VERTICAL
            addView(recentRow, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            clock.setTextColor(Color.WHITE)
            clock.textSize = 17f
            clock.typeface = Typeface.DEFAULT_BOLD
            clock.gravity = Gravity.CENTER
            addView(clock, LayoutParams(dp(85), LayoutParams.MATCH_PARENT))
        }

    private fun renderRecents() {
        recentRow.removeAllViews()
        if (recents.isEmpty()) {
            recentRow.addView(TextView(context).apply {
                text = mode.label + " · " + apps.size + " apps"
                setTextColor(Color.rgb(160, 170, 190))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, 0, 0)
            })
            return
        }

        recents.forEach { app ->
            recentRow.addView(Button(context).apply {
                text = app.label.take(12)
                isAllCaps = false
                textSize = 11f
                setOnClickListener { launch(app) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun taskButton(label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(Color.argb(90, 255, 255, 255), dp(14), null)
            setOnClickListener { action() }
        }

    private fun deviceStatus(): String {
        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: 0

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val network = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos"
            else -> "Online"
        }
        return network + " · " + battery + "%"
    }

    private fun columns(): Int = if (mode == DesktopMode.TV) 5 else 6

    private fun rounded(color: Int, radius: Int, strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) setStroke(dp(2), strokeColor)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
