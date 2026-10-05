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
import android.view.WindowManager
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
    private var shellVisible = true
    private var pointerProfile = "balanced"
    private var trackpadFocused = true
    private val recentPackages = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("hermes_desktop", MODE_PRIVATE) }

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
        pointerProfile = prefs.getString("pointer_profile", "balanced") ?: "balanced"
        mode = if (prefs.getString("desktop_mode", "desktop") == "tv") {
            DesktopMode.TV
        } else {
            DesktopMode.DESKTOP
        }
        recentPackages.clear()
        recentPackages += prefs.getString("recent_packages", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .take(5)
        shizuku = ShizukuBridge(this) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    syncMouseBackendForCurrentMode()
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
        // Stop Hermes' virtual mouse before launching onto DeX so Samsung keeps
        // the physical mouse as the active pointer device.
        syncMouseBackendForCurrentMode()
        if (shellVisible) attachBestExternalDisplay()
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
        runCatching { shizuku.stopVirtualMouse() }
        shizuku.close()
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) {
        syncMouseBackendForCurrentMode()
        attachBestExternalDisplay()
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (externalDisplayId == displayId) {
            presentation?.dismiss()
            presentation = null
            shellView = null
            externalDisplayId = null
            externalDisplayName = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            shellVisible = true
            setContentView(phoneController())
        }
    }

    override fun onDisplayChanged(displayId: Int) {
        syncMouseBackendForCurrentMode()
        if (shellVisible) attachBestExternalDisplay()
    }

    private fun isSamsungDexDualMode(): Boolean {
        val desktopModeManager = applicationContext.getSystemService("desktopmode") ?: return false
        return runCatching {
            val state = desktopModeManager.javaClass
                .getDeclaredMethod("getDesktopModeState")
                .apply { isAccessible = true }
                .invoke(desktopModeManager)
                ?: return@runCatching false

            val stateClass = state.javaClass
            val enabled = (stateClass.getDeclaredMethod("getEnabled")
                .apply { isAccessible = true }
                .invoke(state) as Number).toInt()
            val displayType = (stateClass.getDeclaredMethod("getDisplayType")
                .apply { isAccessible = true }
                .invoke(state) as Number).toInt()

            val enabledValue = stateClass.getDeclaredField("ENABLED")
                .apply { isAccessible = true }
                .getInt(state)
            val dualValue = stateClass.getDeclaredField("DISPLAY_TYPE_DUAL")
                .apply { isAccessible = true }
                .getInt(state)

            enabled == enabledValue && displayType == dualValue
        }.getOrDefault(false)
    }

    private fun samsungDexDesktopDisplay(): Display? =
        runCatching {
            displayManager
                .getDisplays("com.samsung.android.hardware.display.category.DESKTOP")
                .firstOrNull { display ->
                    display.state == Display.STATE_ON &&
                        display.displayId != Display.DEFAULT_DISPLAY
                }
        }.getOrNull()

    private fun physicalExternalDisplay(): Display? {
        val primaryId = windowManager.defaultDisplay.displayId
        return displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { display ->
                display.displayId != primaryId && display.state == Display.STATE_ON
            }
            ?: displayManager.displays.firstOrNull { display ->
                display.displayId != primaryId && display.state == Display.STATE_ON
            }
    }

    private fun resolveHermesDesktopTarget(): Display? {
        if (isSamsungDexDualMode()) {
            samsungDexDesktopDisplay()?.let { return it }
        }
        return physicalExternalDisplay()
    }

    private fun virtualMouseAllowed(): Boolean =
        ::shizuku.isInitialized && shizuku.isReady && !isSamsungDexDualMode()

    private fun syncMouseBackendForCurrentMode() {
        if (!::shizuku.isInitialized || !shizuku.isReady) return

        if (isSamsungDexDualMode()) {
            // DeX already owns the physical USB/Bluetooth mouse and routes it through
            // viewport type 100. A second uinput mouse can become mConnectedMouse and
            // interfere with Samsung's native cursor routing, so keep it stopped.
            runCatching { shizuku.stopVirtualMouse() }
            trackpadFocused = false
        } else {
            shizuku.startVirtualMouse()
            trackpadFocused = true
        }
    }

    @Suppress("DEPRECATION")
    private fun attachBestExternalDisplay(force: Boolean = false) {
        val target = resolveHermesDesktopTarget() ?: return

        if (!force && externalDisplayId == target.displayId) return

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
        externalDisplayName = if (isSamsungDexDualMode()) {
            "Hermes • DeX Desktop"
        } else {
            target.name
        }

        // The phone stays touchable as a controller without stealing mouse/keyboard
        // focus from the desktop task running on the external/DeX logical display.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        shellVisible = true
        launchDesktopActivity(target.displayId)

        syncMouseBackendForCurrentMode()
        setContentView(phoneController())
    }

    private fun desktopShell(displayId: Int, displayContext: android.content.Context): DesktopShellView =
        DesktopShellView(
            context = displayContext,
            apps = apps,
            initialMode = mode,
            initialRecents = recentPackages.mapNotNull { pkg ->
                apps.firstOrNull { it.packageName == pkg }
            },
            onLaunchApp = { launchApp(it, displayId) },
            onOpenTermux = { launchPackage("com.termux", displayId) },
            onStartHermes = { TermuxBridge.startHermes(this) }
        ).also {
            shellView = it
            if (searchQuery.isNotBlank()) it.setSearchQuery(searchQuery)
        }

    private fun phoneController(): View {
        if (externalDisplayId != null && trackpadFocused && virtualMouseAllowed()) {
            return dexTrackpadController()
        }

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

        root.addView(pointerProfileRow(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply {
            topMargin = dp(7)
        })

        if (externalDisplayId != null) {
            root.addView(
                TouchpadView(
                    this,
                    onMove = { dx, dy, dragging -> handlePointerMove(dx, dy, dragging) },
                    onTap = {
                        if (virtualMouseAllowed() && shizuku.startVirtualMouse()) {
                            shizuku.virtualMouseClick(1)
                        } else {
                            shellView?.activateSelection()
                        }
                    },
                    onSecondaryTap = {
                        if (virtualMouseAllowed() && shizuku.startVirtualMouse()) {
                            shizuku.virtualMouseClick(2)
                        }
                    },
                    onScroll = { dy ->
                        if (virtualMouseAllowed() && shizuku.startVirtualMouse()) {
                            shizuku.virtualMouseScroll(if (dy > 0) -1 else 1)
                        } else {
                            shellView?.navigate(0, if (dy > 0) 1 else -1)
                        }
                    },
                    onDragStart = {
                        if (virtualMouseAllowed() && shizuku.startVirtualMouse()) {
                            shizuku.virtualMouseButton(1, true)
                        }
                    },
                    onDragEnd = {
                        if (shizuku.isReady) shizuku.virtualMouseButton(1, false)
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
            showDesktopHome()
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
        toolsRow.addView(wideAction("⌁", "Touchpad") {
            trackpadFocused = true
            setContentView(phoneController())
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        toolsRow.addView(wideAction("H", "Hermes") {
            TermuxBridge.startHermes(this)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
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

    private fun dexTrackpadController(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(bgTop, Color.rgb(9, 14, 23), bgBottom)
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(6))
            background = rounded(surface, dp(20), border, 1)

            addView(TextView(this@MainActivity).apply {
                text = "H"
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(bgTop)
                background = rounded(accent, dp(14), null, 0)
            }, LinearLayout.LayoutParams(dp(42), dp(42)))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "Hermes Trackpad"
                    setTextColor(textPrimary)
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = (externalDisplayName ?: "Pantalla") + "  •  mouse de sistema"
                    setTextColor(textSecondary)
                    textSize = 9.5f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

            addView(TextView(this@MainActivity).apply {
                text = if (shizuku.isReady) "SYSTEM" else "SETUP"
                setTextColor(if (shizuku.isReady) success else textSecondary)
                textSize = 8.5f
                letterSpacing = 0.10f
                gravity = Gravity.CENTER
                background = rounded(
                    if (shizuku.isReady) Color.argb(38, 112, 226, 154) else surfaceSoft,
                    dp(11),
                    if (shizuku.isReady) Color.argb(70, 112, 226, 154) else border,
                    1
                )
            }, LinearLayout.LayoutParams(dp(62), dp(30)))

            addView(TextView(this@MainActivity).apply {
                text = "Panel"
                setTextColor(textSecondary)
                textSize = 9f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = rounded(surfaceSoft, dp(11), border, 1)
                setOnClickListener {
                    trackpadFocused = false
                    setContentView(phoneController())
                }
            }, LinearLayout.LayoutParams(dp(62), dp(30)).apply {
                marginStart = dp(6)
            })
        }
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(62)
        ))

        root.addView(pointerProfileRow(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42)
        ).apply {
            topMargin = dp(7)
        })

        root.addView(
            TouchpadView(
                this,
                onMove = { dx, dy, dragging -> handlePointerMove(dx, dy, dragging) },
                onTap = {
                    if (virtualMouseAllowed() && shizuku.startVirtualMouse()) {
                        shizuku.virtualMouseClick(1)
                    } else {
                        shellView?.activateSelection()
                    }
                },
                onSecondaryTap = {
                    if (virtualMouseAllowed() && shizuku.startVirtualMouse()) {
                        shizuku.virtualMouseClick(2)
                    }
                },
                onScroll = { dy ->
                    if (virtualMouseAllowed() && shizuku.startVirtualMouse()) {
                        shizuku.virtualMouseScroll(if (dy > 0) -1 else 1)
                    } else {
                        shellView?.navigate(0, if (dy > 0) 1 else -1)
                    }
                },
                onDragStart = {
                    if (virtualMouseAllowed() && shizuku.startVirtualMouse()) {
                        shizuku.virtualMouseButton(1, true)
                    }
                },
                onDragEnd = {
                    if (shizuku.isReady) shizuku.virtualMouseButton(1, false)
                },
                privileged = { shizuku.isReady }
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        nav.addView(actionButton("←", "Atrás") {
            navigationAction(KeyEvent.KEYCODE_BACK)
        }, rowWeight())
        nav.addView(actionButton("●", "Inicio") {
            showDesktopHome()
        }, rowWeight())
        nav.addView(actionButton("▣", "Recientes") {
            val id = externalDisplayId
            val sent = id != null && shizuku.isReady &&
                shizuku.key(id, KeyEvent.KEYCODE_APP_SWITCH)
            if (!sent) shellView?.cycleRecent()
        }, rowWeight())
        nav.addView(actionButton("⌨", "Panel") {
            trackpadFocused = false
            setContentView(phoneController())
        }, rowWeight())
        root.addView(nav, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(62)
        ))

        return root
    }

    private fun pointerProfileRow(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(70, 17, 23, 34), dp(14), border, 1)
            setPadding(dp(4), dp(4), dp(4), dp(4))

            addView(segmentButton("Precisión", pointerProfile == "precision") {
                setPointerProfile("precision")
            }, rowWeight())
            addView(segmentButton("Equilibrado", pointerProfile == "balanced") {
                setPointerProfile("balanced")
            }, rowWeight())
            addView(segmentButton("Rápido", pointerProfile == "fast") {
                setPointerProfile("fast")
            }, rowWeight())
        }

    private fun setPointerProfile(profile: String) {
        if (pointerProfile == profile) return
        pointerProfile = profile
        prefs.edit().putString("pointer_profile", profile).apply()
        setContentView(phoneController())
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

        if (isSamsungDexDualMode()) {
            // In DeX Dual Mode the physical mouse belongs to Samsung's native input
            // pipeline. Do not inject pointer events from the phone touchpad.
            return
        }

        if (!shizuku.isReady) {
            if (abs(dx) > abs(dy)) {
                shellView?.navigate(if (dx > 0) 1 else -1, 0)
            } else {
                shellView?.navigate(0, if (dy > 0) 1 else -1)
            }
            return
        }

        // Real uinput mouse: Android owns pointer position and applies the same
        // pointer acceleration/ballistics used by USB/Bluetooth mice.
        val scale = when (pointerProfile) {
            "precision" -> 0.72f
            "fast" -> 1.35f
            else -> 1.0f
        }

        val moved = shizuku.startVirtualMouse() &&
            shizuku.virtualMouseMove(dx * scale, dy * scale)

        // Compatibility fallback for devices where uinput is blocked.
        if (!moved) {
            val fallbackScale = if (dragging) scale * 0.8f else scale
            cursorX = (cursorX + dx * fallbackScale).coerceIn(1f, displayWidth.toFloat() - 2f)
            cursorY = (cursorY + dy * fallbackScale).coerceIn(1f, displayHeight.toFloat() - 2f)
            shizuku.movePointer(id, cursorX, cursorY, dragging)
        }
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
        prefs.edit()
            .putString("desktop_mode", if (newMode == DesktopMode.TV) "tv" else "desktop")
            .apply()

        val id = externalDisplayId
        if (id != null && shellVisible) {
            launchDesktopActivity(id)
        }
        setContentView(phoneController())
    }

    private fun launchApp(app: AppEntry, displayId: Int) {
        rememberRecent(app)
        launchPackage(app.packageName, displayId)
    }

    private fun rememberRecent(app: AppEntry) {
        recentPackages.remove(app.packageName)
        recentPackages.add(0, app.packageName)
        while (recentPackages.size > 5) recentPackages.removeAt(recentPackages.lastIndex)
        prefs.edit().putString("recent_packages", recentPackages.joinToString("|")).apply()
    }

    private fun hideDesktopShell() {
        shellVisible = false
        presentation?.dismiss()
        presentation = null
        shellView = null
        setContentView(phoneController())
    }

    private fun showDesktopHome() {
        shellVisible = true
        val id = externalDisplayId
        if (id != null) {
            launchDesktopActivity(id)
        } else {
            attachBestExternalDisplay(force = true)
        }
    }

    private fun launchDesktopActivity(displayId: Int) {
        val intent = Intent(this, DesktopActivity::class.java).apply {
            // Recreate the dedicated desktop task on the requested display. Reordering
            // an existing single task leaves it pinned to its previous DisplayContent.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(
                DesktopActivity.EXTRA_MODE,
                if (mode == DesktopMode.TV) "tv" else "desktop"
            )
        }

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
        }

        runCatching {
            startActivity(intent, options.toBundle())
        }.onFailure { error ->
            Toast.makeText(
                this,
                "No se pudo abrir Hermes en el monitor: " + error.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchPackage(packageName: String, displayId: Int) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, "No se pudo abrir " + packageName, Toast.LENGTH_SHORT).show()
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()

        hideDesktopShell()

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
            shellVisible = true
            attachBestExternalDisplay(force = true)
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
