package com.hermes.desktop

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
    private var mode = DesktopMode.DESKTOP
    private var searchQuery = ""

    private var displayWidth = 1920
    private var displayHeight = 1080
    private var cursorX = displayWidth / 2f
    private var cursorY = displayHeight / 2f

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

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
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
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(8, 12, 20))
        }

        root.addView(TextView(this).apply {
            text = "HERMES DESKTOP 0.3"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = if (externalDisplayId == null)
                "Sin pantalla externa · " + shizuku.stateLabel()
            else
                "Pantalla #" + externalDisplayId + " · " + mode.label + " · " + shizuku.stateLabel()
            setTextColor(if (shizuku.isReady) Color.rgb(126, 226, 154) else Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
        })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modeRow.addView(actionButton("🖥 Shell") {
            setMode(DesktopMode.DESKTOP)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        modeRow.addView(actionButton("📺 TV") {
            setMode(DesktopMode.TV)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        modeRow.addView(actionButton("⚡ Shizuku") {
            shizuku.requestOrConnect()
            setContentView(phoneController())
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(modeRow)

        val search = EditText(this).apply {
            hint = "Buscar app…"
            setText(searchQuery)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
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
        ))

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
                )
            )
        } else {
            root.addView(TextView(this).apply {
                text = "El touchpad aparecerá cuando Android exponga una segunda pantalla."
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }

        val windowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        windowRow.addView(actionButton("⇄ Alt-Tab") {
            privilegedCombo(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        windowRow.addView(actionButton("▣ Ventana") {
            privilegedCombo(
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_DPAD_DOWN
            )
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        windowRow.addView(actionButton("▰ Pantalla") {
            privilegedCombo(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_H)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(windowRow)

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        navRow.addView(actionButton("← Atrás") {
            navigationAction(KeyEvent.KEYCODE_BACK, AccessibilityService.GLOBAL_ACTION_BACK)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        navRow.addView(actionButton("● Inicio") {
            navigationAction(KeyEvent.KEYCODE_HOME, AccessibilityService.GLOBAL_ACTION_HOME)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        navRow.addView(actionButton("▣ Recientes") {
            val id = externalDisplayId
            val sent = id != null && shizuku.isReady &&
                shizuku.key(id, KeyEvent.KEYCODE_APP_SWITCH)

            if (!sent &&
                !HermesAccessibilityService.perform(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ) {
                shellView?.cycleRecent()
                openAccessibilityHelp()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(navRow)

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolsRow.addView(actionButton("⌨ Teclado") {
            search.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolsRow.addView(actionButton("🤖 Hermes") {
            TermuxBridge.startHermes(this)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolsRow.addView(actionButton(">_ Termux") {
            externalDisplayId?.let { launchPackage("com.termux", it) }
                ?: TermuxBridge.openTermux(this)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(toolsRow)

        root.addView(TextView(this).apply {
            text = if (shizuku.isReady)
                "Mouse real activo · 1 dedo mueve · tap clic · 2 dedos scroll · mantener y mover arrastra"
            else
                "Sin Shizuku: touchpad del launcher. Activa Shizuku para mouse real."
            setTextColor(Color.GRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        })

        return root
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
            Toast.makeText(
                this,
                "Requiere Shizuku y soporte del modo escritorio del dispositivo.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun navigationAction(keyCode: Int, accessibilityAction: Int) {
        val id = externalDisplayId
        val sent = id != null && shizuku.isReady && shizuku.key(id, keyCode)

        if (!sent && !HermesAccessibilityService.perform(accessibilityAction)) {
            openAccessibilityHelp()
        }
    }

    private fun setMode(newMode: DesktopMode) {
        if (mode == newMode) return
        mode = newMode
        if (externalDisplayId != null) attachBestExternalDisplay(force = true)
        else setContentView(phoneController())
    }

    private fun openAccessibilityHelp() {
        Toast.makeText(
            this,
            "Activa “Hermes Desktop controls” en Accesibilidad como fallback.",
            Toast.LENGTH_LONG
        ).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun actionButton(textValue: String, action: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 12f
            setOnClickListener { action() }
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
