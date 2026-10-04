package com.hermes.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity(), DisplayManager.DisplayListener {

    private lateinit var displayManager: DisplayManager
    private lateinit var apps: List<AppEntry>
    private lateinit var shizuku: ShizukuBridge

    private var presentation: DesktopPresentation? = null
    private var shellView: DesktopShellView? = null
    private var externalDisplayId: Int? = null
    private var externalDisplayName: String? = null
    private var mode = DesktopMode.DESKTOP
    private var searchQuery = ""

    private var displayWidth = 1920
    private var displayHeight = 1080
    private var cursorX = displayWidth / 2f
    private var cursorY = displayHeight / 2f

    private val bgTop = Color.rgb(7, 11, 19)
    private val bgBottom = Color.rgb(12, 18, 29)
    private val surface = Color.argb(150, 24, 31, 45)
    private val surfaceSoft = Color.argb(65, 255, 255, 255)
    private val border = Color.argb(52, 255, 255, 255)
    private val textPrimary = Color.rgb(244, 247, 252)
    private val textSecondary = Color.rgb(145, 159, 182)
    private val accent = Color.rgb(104, 213, 255)
    private val accentSoft = Color.argb(52, 104, 213, 255)
    private val success = Color.rgb(112, 226, 154)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayManager = getSystemService(DisplayManager::class.java)
        apps = AppRepository(this).launcherApps()
        shizuku = ShizukuBridge(this) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    setContentView(phoneController())
                }
            }
        }

        window.statusBarColor = bgTop
        window.navigationBarColor = bgTop
        setContentView(phoneController())
    }

    override fun onResume() {
        super.onResume()
        displayManager.registerDisplayListener(this, null)
        attachBestExternalDisplay()
        setContentView(phoneController())
    }

    override fun onPause() {
        displayManager.unregisterDisplayListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        presentation?.dismiss()
        presentation = null
        shellView = null
        shizuku.close()
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) = attachBestExternalDisplay()

    override fun onDisplayRemoved(displayId: Int) {
        if (externalDisplayId == displayId) {
            presentation?.dismiss()
            presentation = null
            shellView = null
            externalDisplayId = null
            externalDisplayName = null
            setContentView(phoneController())
        }
    }

    override fun onDisplayChanged(displayId: Int) = Unit

    @Suppress("DEPRECATION")
    private fun attachBestExternalDisplay(force: Boolean = false) {
        val primaryId = windowManager.defaultDisplay.displayId
        val target = displayManager.displays
            .firstOrNull { it.displayId != primaryId && it.state == Display.STATE_ON }
            ?: return

        if (!force &&
            externalDisplayId == target.displayId &&
            presentation?.isShowing == true
        ) return

        val metrics = android.util.DisplayMetrics()
        target.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            cursorX = displayWidth / 2f
            cursorY = displayHeight / 2f
        }

        presentation?.dismiss()
        shellView = null
        externalDisplayId = target.displayId
        externalDisplayName = target.name

        presentation = DesktopPresentation(this, target) {
            desktopShell(target.displayId)
        }.also {
            runCatching { it.show() }
                .onFailure { error ->
                    Toast.makeText(
                        this,
                        "No se pudo abrir escritorio: " + error.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
        }

        setContentView(phoneController())
    }

    private fun desktopShell(displayId: Int): DesktopShellView =
        DesktopShellView(
            context = this,
            apps = apps,
            initialMode = mode,
            onLaunchApp = { launchApp(it, displayId) },
            onOpenTermux = { launchPackage("com.termux", displayId) },
            onStartHermes = { TermuxBridge.startHermes(this) }
        ).also {
            shellView = it
            if (searchQuery.isNotBlank()) it.setSearchQuery(searchQuery)
        }

    private fun phoneController(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(bgTop, Color.rgb(10, 15, 25), bgBottom)
            )
        }

        root.addView(controllerHeader(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(72)
        ))

        root.addView(modeSwitcher(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            topMargin = dp(8)
        })

        val search = EditText(this).apply {
            hint = "Buscar en el escritorio"
            setText(searchQuery)
            setTextColor(textPrimary)
            setHintTextColor(Color.rgb(102, 116, 139))
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.argb(95, 19, 26, 39), dp(17), border, 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    shellView?.setSearchQuery(searchQuery)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply {
            topMargin = dp(8)
        })

        if (externalDisplayId != null) {
            root.addView(
                TouchpadView(
                    this,
                    onMove = { dx, dy, dragging -> handlePointerMove(dx, dy, dragging) },
                    onTap = {
                        val id = externalDisplayId
                        if (id != null && shizuku.isReady) {
                            shizuku.click(id, cursorX, cursorY)
                        } else {
                            shellView?.activateSelection()
                        }
                    },
                    onScroll = { dy ->
                        val id = externalDisplayId
                        if (id != null && shizuku.isReady) {
                            shizuku.scroll(id, cursorX, cursorY, if (dy > 0) -1f else 1f)
                        } else {
                            shellView?.navigate(0, if (dy > 0) 1 else -1)
                        }
                    },
                    onDragStart = {
                        externalDisplayId?.let {
                            if (shizuku.isReady) shizuku.pointerButton(it, cursorX, cursorY, true)
                        }
                    },
                    onDragEnd = {
                        externalDisplayId?.let {
                            if (shizuku.isReady) shizuku.pointerButton(it, cursorX, cursorY, false)
                        }
                    },
                    privileged = { shizuku.isReady }
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    topMargin = dp(10)
                }
            )
        } else {
            root.addView(
                emptyDisplayCard(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    topMargin = dp(10)
                }
            )
        }

        root.addView(sectionLabel("VENTANAS"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(25)
        ).apply {
            topMargin = dp(5)
        })

        val windowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        windowRow.addView(actionButton("⇄", "Cambiar") {
            privilegedCombo(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB)
        }, rowWeight())
        windowRow.addView(actionButton("▣", "Ventana") {
            privilegedCombo(
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_DPAD_DOWN
            )
        }, rowWeight())
        windowRow.addView(actionButton("▰", "Pantalla") {
            privilegedCombo(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_H)
        }, rowWeight())
        root.addView(windowRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(58)
        ))

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        navRow.addView(actionButton("←", "Atrás") {
            navigationAction(KeyEvent.KEYCODE_BACK)
        }, rowWeight())
        navRow.addView(actionButton("●", "Inicio") {
            navigationAction(KeyEvent.KEYCODE_HOME)
        }, rowWeight())
        navRow.addView(actionButton("▣", "Recientes") {
            val id = externalDisplayId
            val sent = id != null && shizuku.isReady &&
                shizuku.key(id, KeyEvent.KEYCODE_APP_SWITCH)

            if (!sent) {
                shellView?.cycleRecent()
                showShizukuHint()
            }
        }, rowWeight())
        navRow.addView(actionButton("⌨", "Teclado") {
            search.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }, rowWeight())
        root.addView(navRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(62)
        ))

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        toolsRow.addView(wideAction("H", "Hermes") {
            TermuxBridge.startHermes(this)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        toolsRow.addView(wideAction(">_", "Termux") {
            externalDisplayId?.let { launchPackage("com.termux", it) }
                ?: TermuxBridge.openTermux(this)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        root.addView(toolsRow)

        root.addView(TextView(this).apply {
            text = if (shizuku.isReady)
                "MOUSE LIVE  •  Shizuku activo  •  ${displayWidth}×${displayHeight}"
            else
                "Activa Shizuku para mouse, clic, scroll y control de ventanas"
            setTextColor(if (shizuku.isReady) Color.rgb(126, 206, 180) else textSecondary)
            textSize = 9.5f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        return root
    }

    private fun controllerHeader(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = rounded(surface, dp(22), border, 1)

            addView(TextView(this@MainActivity).apply {
                text = "H"
                gravity = Gravity.CENTER
                textSize = 19f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(bgTop)
                background = rounded(accent, dp(15), null, 0)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)

                addView(TextView(this@MainActivity).apply {
                    text = "Hermes Control"
                    setTextColor(textPrimary)
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = if (externalDisplayId == null)
                        "Esperando pantalla externa"
                    else
                        (externalDisplayName ?: "Pantalla") + "  •  #" + externalDisplayId
                    setTextColor(textSecondary)
                    textSize = 10.5f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

            addView(TextView(this@MainActivity).apply {
                text = if (externalDisplayId != null && shizuku.isReady) "READY" else "SETUP"
                setTextColor(if (externalDisplayId != null && shizuku.isReady) success else textSecondary)
                textSize = 9f
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
                background = rounded(
                    if (externalDisplayId != null && shizuku.isReady)
                        Color.argb(38, 112, 226, 154)
                    else
                        surfaceSoft,
                    dp(12),
                    if (externalDisplayId != null && shizuku.isReady)
                        Color.argb(75, 112, 226, 154)
                    else border,
                    1
                )
            }, LinearLayout.LayoutParams(dp(64), dp(30)))
        }

    private fun modeSwitcher(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.argb(85, 17, 23, 34), dp(16), border, 1)
            setPadding(dp(4), dp(4), dp(4), dp(4))

            addView(segmentButton("Escritorio", mode == DesktopMode.DESKTOP) {
                setMode(DesktopMode.DESKTOP)
            }, rowWeight())
            addView(segmentButton("TV", mode == DesktopMode.TV) {
                setMode(DesktopMode.TV)
            }, rowWeight())
            addView(segmentButton(
                if (shizuku.isReady) "Shizuku ✓" else "Shizuku",
                shizuku.isReady
            ) {
                shizuku.requestOrConnect()
                setContentView(phoneController())
            }, rowWeight())
        }

    private fun emptyDisplayCard(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.argb(70, 255, 255, 255), dp(26), border, 1)

            addView(TextView(this@MainActivity).apply {
                text = "▱"
                textSize = 34f
                setTextColor(accent)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "Conecta una pantalla"
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "USB-C → HDMI o display compatible"
                textSize = 11f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }

    private fun sectionLabel(value: String): TextView =
        TextView(this).apply {
            text = value
            setTextColor(Color.rgb(102, 118, 144))
            textSize = 9f
            letterSpacing = 0.16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }

    private fun segmentButton(label: String, selected: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(if (selected) textPrimary else textSecondary)
            background = rounded(
                if (selected) Color.argb(95, 39, 55, 75) else Color.TRANSPARENT,
                dp(12),
                if (selected) Color.argb(70, 104, 213, 255) else null,
                if (selected) 1 else 0
            )
            setOnClickListener { action() }
        }

    private fun actionButton(glyph: String, label: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(5), dp(4), dp(4))
            background = rounded(Color.argb(62, 255, 255, 255), dp(16), border, 1)
            elevation = dp(1).toFloat()
            setOnClickListener { action() }

            addView(TextView(this@MainActivity).apply {
                text = glyph
                textSize = 16f
                setTextColor(textPrimary)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 8.5f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
            })
        }

    private fun wideAction(glyph: String, label: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(Color.argb(75, 255, 255, 255), dp(16), border, 1)
            setOnClickListener { action() }

            addView(TextView(this@MainActivity).apply {
                text = glyph
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (glyph == "H") accent else textPrimary)
                gravity = Gravity.CENTER
                setPadding(0, 0, dp(8), 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                setTextColor(textPrimary)
                gravity = Gravity.CENTER
            })
        }

    private fun rowWeight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        }

    private fun handlePointerMove(dx: Float, dy: Float, dragging: Boolean) {
        val id = externalDisplayId ?: return

        if (!shizuku.isReady) {
            if (abs(dx) > abs(dy)) {
                shellView?.navigate(if (dx > 0) 1 else -1, 0)
            } else {
                shellView?.navigate(0, if (dy > 0) 1 else -1)
            }
            return
        }

        cursorX = (cursorX + dx).coerceIn(1f, displayWidth.toFloat() - 2f)
        cursorY = (cursorY + dy).coerceIn(1f, displayHeight.toFloat() - 2f)
        shizuku.movePointer(id, cursorX, cursorY, dragging)
    }

    private fun privilegedCombo(vararg keyCodes: Int) {
        val id = externalDisplayId
        if (id == null || !shizuku.isReady || !shizuku.keyCombination(id, *keyCodes)) {
            showShizukuHint()
        }
    }

    private fun navigationAction(keyCode: Int) {
        val id = externalDisplayId
        val sent = id != null && shizuku.isReady && shizuku.key(id, keyCode)
        if (!sent) showShizukuHint()
    }

    private fun showShizukuHint() {
        Toast.makeText(
            this,
            "Activa Shizuku para controles del sistema.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setMode(newMode: DesktopMode) {
        if (mode == newMode) return
        mode = newMode
        if (externalDisplayId != null) attachBestExternalDisplay(force = true)
        else setContentView(phoneController())
    }

    private fun launchApp(app: AppEntry, displayId: Int) =
        launchPackage(app.packageName, displayId)

    @Suppress("DEPRECATION")
    private fun launchPackage(packageName: String, displayId: Int) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, "No se pudo abrir " + packageName, Toast.LENGTH_SHORT).show()
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()

        runCatching {
            options.launchDisplayId = displayId
            val target = displayManager.getDisplay(displayId)
            val metrics = android.util.DisplayMetrics()
            target?.getRealMetrics(metrics)

            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                options.launchBounds = Rect(
                    metrics.widthPixels / 12,
                    metrics.heightPixels / 10,
                    metrics.widthPixels * 11 / 12,
                    metrics.heightPixels * 9 / 10
                )
            }
            startActivity(intent, options.toBundle())
        }.recoverCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(
                this,
                "Android bloqueó el lanzamiento en esa pantalla",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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
}
