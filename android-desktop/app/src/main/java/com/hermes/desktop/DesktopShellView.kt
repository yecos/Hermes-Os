package com.hermes.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
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
    initialRecents: List<AppEntry> = emptyList(),
    private val onLaunchApp: (AppEntry) -> Unit,
    private val onOpenTermux: () -> Unit,
    private val onStartHermes: () -> Unit
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val clock = TextView(context)
    private val status = TextView(context)
    private val commandText = TextView(context)
    private val appCount = TextView(context)
    private val grid = GridLayout(context)
    private val scroll = ScrollView(context)
    private val recentRow = LinearLayout(context)
    private lateinit var commandBar: LinearLayout

    private var mode = initialMode
    private var paletteActive = false
    private var keyboardQuery = ""
    private var filteredApps = apps
    private var selectedIndex = 0
    private val appTiles = mutableListOf<View>()
    private val recents = initialRecents.take(5).toMutableList()

    private val bgTop = Color.rgb(7, 12, 22)
    private val bgBottom = Color.rgb(13, 20, 34)
    private val surface = Color.argb(150, 24, 32, 48)
    private val surfaceSoft = Color.argb(82, 255, 255, 255)
    private val border = Color.argb(58, 255, 255, 255)
    private val textPrimary = Color.rgb(244, 247, 252)
    private val textSecondary = Color.rgb(155, 168, 191)
    private val accent = Color.rgb(104, 213, 255)
    private val accentSoft = Color.argb(64, 104, 213, 255)

    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            status.text = deviceStatus()
            handler.postDelayed(this, 30_000)
        }
    }

    init {
        isFocusableInTouchMode = true
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(bgTop, Color.rgb(11, 17, 31), bgBottom)
        )

        addView(AmbientView(context), LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }

        root.addView(topBar(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(70)
        ))
        root.addView(workspaceHeader(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(66)
        ))
        root.addView(appArea(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(bottomDock(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(84)
        ))

        addView(root, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))

        renderApps()
        renderRecents()
        handler.post(clockTick)
        post { requestFocus() }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clockTick)
        super.onDetachedFromWindow()
    }

    fun handleExternalKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        if ((event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_K) ||
            event.keyCode == KeyEvent.KEYCODE_SLASH ||
            event.keyCode == KeyEvent.KEYCODE_SEARCH
        ) {
            openCommandPalette()
            return true
        }

        if (paletteActive) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ESCAPE -> {
                    paletteActive = false
                    keyboardQuery = ""
                    setSearchQuery("")
                    updatePaletteVisual()
                    return true
                }
                KeyEvent.KEYCODE_DEL -> {
                    if (keyboardQuery.isNotEmpty()) {
                        keyboardQuery = keyboardQuery.dropLast(1)
                        setSearchQuery(keyboardQuery)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { navigate(-1, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { navigate(1, 0); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { navigate(0, -1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { navigate(0, 1); return true }
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    activateSelection()
                    paletteActive = false
                    updatePaletteVisual()
                    return true
                }
            }

            val unicode = event.unicodeChar
            if (unicode > 0 && !event.isCtrlPressed && !event.isAltPressed) {
                val ch = unicode.toChar()
                if (!ch.isISOControl()) {
                    keyboardQuery += ch
                    setSearchQuery(keyboardQuery)
                    return true
                }
            }
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { navigate(-1, 0); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { navigate(1, 0); true }
            KeyEvent.KEYCODE_DPAD_UP -> { navigate(0, -1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { navigate(0, 1); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> { activateSelection(); true }
            else -> false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleExternalKey(event) || super.dispatchKeyEvent(event)

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
        filteredApps.getOrNull(selectedIndex)?.let { launch(it) }
    }

    fun setSearchQuery(query: String) {
        keyboardQuery = query
        filteredApps = if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        commandText.text = when {
            paletteActive && query.isBlank() -> "Escribe para buscar…"
            query.isBlank() -> "Buscar apps, comandos y espacios"
            else -> query
        }
        selectedIndex = 0
        renderApps()
        if (::commandBar.isInitialized) updatePaletteVisual()
    }

    private fun openCommandPalette() {
        paletteActive = true
        keyboardQuery = ""
        setSearchQuery("")
        updatePaletteVisual()
        requestFocus()
    }

    private fun updatePaletteVisual() {
        if (!::commandBar.isInitialized) return
        commandBar.background = rounded(
            if (paletteActive) Color.argb(170, 18, 35, 52) else Color.argb(120, 18, 25, 39),
            dp(18),
            if (paletteActive) Color.argb(180, 104, 213, 255) else border,
            if (paletteActive) 2 else 1
        )
        commandText.setTextColor(if (paletteActive) textPrimary else textSecondary)
    }

    fun setMode(newMode: DesktopMode) {
        if (mode == newMode) return
        mode = newMode
        selectedIndex = 0
        renderApps()
    }

    fun cycleRecent() {
        recents.firstOrNull()?.let { launch(it) }
    }

    private fun topBar(): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val brand = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(context).apply {
                    text = "H"
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(7, 12, 22))
                    textSize = 19f
                    typeface = Typeface.DEFAULT_BOLD
                    background = rounded(accent, dp(15), null, 0)
                }, LinearLayout.LayoutParams(dp(44), dp(44)))

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(TextView(context).apply {
                        text = "HERMES"
                        setTextColor(textPrimary)
                        textSize = 17f
                        letterSpacing = 0.12f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(context).apply {
                        text = "DESKTOP"
                        setTextColor(textSecondary)
                        textSize = 10f
                        letterSpacing = 0.18f
                    })
                })
            }
            addView(brand, LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.MATCH_PARENT))

            commandBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isClickable = true
                isFocusable = true
                setOnClickListener { openCommandPalette() }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(10), 0)
                background = rounded(Color.argb(120, 18, 25, 39), dp(18), border, 1)

                addView(TextView(context).apply {
                    text = "⌕"
                    textSize = 22f
                    setTextColor(accent)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.MATCH_PARENT))

                commandText.apply {
                    text = "Buscar apps, comandos y espacios"
                    setTextColor(textSecondary)
                    textSize = 13f
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                }
                addView(commandText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

                addView(TextView(context).apply {
                    text = "CTRL  K"
                    setTextColor(Color.rgb(180, 192, 212))
                    textSize = 9f
                    gravity = Gravity.CENTER
                    background = rounded(Color.argb(90, 255, 255, 255), dp(9), null, 0)
                }, LinearLayout.LayoutParams(dp(62), dp(28)))
            }
            addView(commandBar, LinearLayout.LayoutParams(0, dp(48), 1f))

            val system = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(dp(16), 0, 0, 0)

                status.apply {
                    setTextColor(textSecondary)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    background = rounded(Color.argb(75, 255, 255, 255), dp(13), border, 1)
                    setPadding(dp(12), 0, dp(12), 0)
                }
                addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))

                clock.apply {
                    setTextColor(textPrimary)
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
                addView(clock, LinearLayout.LayoutParams(dp(76), dp(42)))
            }
            addView(system, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.MATCH_PARENT))
        }

    private fun workspaceHeader(): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = if (mode == DesktopMode.TV) "Sala" else "Workspace"
                    setTextColor(textPrimary)
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                })
                appCount.apply {
                    setTextColor(textSecondary)
                    textSize = 11f
                }
                addView(appCount)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(context).apply {
                text = if (mode == DesktopMode.TV) "TV MODE" else "DESKTOP MODE"
                setTextColor(accent)
                textSize = 10f
                letterSpacing = 0.15f
                gravity = Gravity.CENTER
                background = rounded(accentSoft, dp(12), Color.argb(80, 104, 213, 255), 1)
                setPadding(dp(14), 0, dp(14), 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        }

    private fun appArea(): View {
        grid.useDefaultMargins = false
        grid.alignmentMode = GridLayout.ALIGN_BOUNDS
        grid.setPadding(dp(2), dp(10), dp(2), dp(24))

        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(grid)
        return scroll
    }

    private fun renderApps() {
        grid.removeAllViews()
        appTiles.clear()
        grid.columnCount = columns()

        val limit = if (mode == DesktopMode.TV) 30 else 48
        filteredApps.take(limit).forEachIndexed { index, app ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                elevation = dp(1).toFloat()
                contentDescription = app.label
                setOnClickListener {
                    selectedIndex = index
                    refreshSelection()
                    launch(app)
                }
            }

            val iconSize = if (mode == DesktopMode.TV) dp(76) else dp(58)
            tile.addView(FrameLayout(context).apply {
                background = rounded(Color.argb(36, 255, 255, 255), dp(19), border, 1)
                addView(ImageView(context).apply {
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }, LinearLayout.LayoutParams(iconSize, iconSize))

            tile.addView(TextView(context).apply {
                text = app.label
                setTextColor(textPrimary)
                textSize = if (mode == DesktopMode.TV) 15f else 12.5f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(
                if (mode == DesktopMode.TV) dp(180) else dp(135),
                if (mode == DesktopMode.TV) dp(54) else dp(46)
            ))

            val lp = GridLayout.LayoutParams().apply {
                width = if (mode == DesktopMode.TV) dp(210) else dp(154)
                height = if (mode == DesktopMode.TV) dp(170) else dp(132)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(tile, lp)
            appTiles += tile
        }

        appCount.text = if (filteredApps.isEmpty()) {
            "Sin resultados"
        } else {
            filteredApps.size.toString() + " apps disponibles"
        }

        selectedIndex = selectedIndex.coerceAtMost((appTiles.size - 1).coerceAtLeast(0))
        refreshSelection()
    }

    private fun refreshSelection() {
        appTiles.forEachIndexed { index, tile ->
            val selected = index == selectedIndex
            tile.background = rounded(
                if (selected) Color.argb(150, 25, 48, 70) else Color.argb(32, 255, 255, 255),
                dp(21),
                if (selected) Color.argb(180, 104, 213, 255) else Color.argb(30, 255, 255, 255),
                if (selected) 2 else 1
            )
            tile.animate()
                .scaleX(if (selected) 1.035f else 1f)
                .scaleY(if (selected) 1.035f else 1f)
                .translationY(if (selected) -dp(2).toFloat() else 0f)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .start()
            tile.elevation = if (selected) dp(12).toFloat() else dp(1).toFloat()
        }

        appTiles.getOrNull(selectedIndex)?.let { selected ->
            scroll.post {
                val target = (selected.top - dp(90)).coerceAtLeast(0)
                scroll.smoothScrollTo(0, target)
            }
        }
    }

    private fun launch(app: AppEntry) {
        recents.removeAll { it.packageName == app.packageName }
        recents.add(0, app)
        while (recents.size > 5) recents.removeAt(recents.lastIndex)
        renderRecents()
        onLaunchApp(app)
    }

    private fun bottomDock(): View =
        FrameLayout(context).apply {
            val dock = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(8), dp(9), dp(8))
                background = rounded(Color.argb(205, 17, 23, 35), dp(24), Color.argb(58, 255, 255, 255), 1)

                addView(dockButton("H", "Hermes", true) { onStartHermes() })
                addView(dockButton(">_", "Termux", false) { onOpenTermux() })

                recentRow.orientation = LinearLayout.HORIZONTAL
                recentRow.gravity = Gravity.CENTER_VERTICAL
                addView(recentRow)
            }

            addView(dock, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(68),
                Gravity.CENTER
            ))
        }

    private fun dockButton(glyph: String, label: String, highlighted: Boolean, action: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            isClickable = true
            isFocusable = true
            background = rounded(
                if (highlighted) accentSoft else Color.TRANSPARENT,
                dp(15),
                null,
                0
            )
            setOnClickListener { action() }

            addView(TextView(context).apply {
                text = glyph
                gravity = Gravity.CENTER
                setTextColor(if (highlighted) accent else textPrimary)
                textSize = if (glyph == "H") 18f else 15f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(dp(38), dp(30)))

            addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(textSecondary)
                textSize = 8f
            })
        }

    private fun renderRecents() {
        recentRow.removeAllViews()
        if (recents.isEmpty()) {
            recentRow.addView(TextView(context).apply {
                text = "   Abre una app para verla aquí   "
                setTextColor(Color.rgb(125, 139, 160))
                textSize = 10f
                gravity = Gravity.CENTER
            })
            return
        }

        recents.forEach { app ->
            recentRow.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(2), dp(7), dp(2))
                isClickable = true
                isFocusable = true
                setOnClickListener { launch(app) }

                addView(ImageView(context).apply {
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(34), dp(34)))

                addView(TextView(context).apply {
                    text = app.label.take(10)
                    setTextColor(textSecondary)
                    textSize = 8f
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            })
        }
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
        return network + "  •  " + battery + "%"
    }

    private fun columns(): Int = if (mode == DesktopMode.TV) 5 else 7

    private fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int?,
        strokeWidth: Int
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(dp(strokeWidth), strokeColor)
            }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class AmbientView(context: Context) : View(context) {
        private val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, 81, 203, 255)
        }
        private val violet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(16, 146, 119, 255)
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(16, 255, 255, 255)
            strokeWidth = dp(1).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawCircle(w * .78f, h * .18f, w * .22f, cyan)
            canvas.drawCircle(w * .12f, h * .78f, w * .20f, violet)
            canvas.drawLine(w * .05f, h * .12f, w * .95f, h * .12f, line)
        }
    }
}
