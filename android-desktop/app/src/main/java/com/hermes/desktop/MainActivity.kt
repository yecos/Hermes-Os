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
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), DisplayManager.DisplayListener {

    private lateinit var displayManager: DisplayManager
    private lateinit var apps: List<AppEntry>

    private var presentation: DesktopPresentation? = null
    private var shellView: DesktopShellView? = null
    private var externalDisplayId: Int? = null
    private var mode = DesktopMode.DESKTOP
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DisplayManager::class.java)
        apps = AppRepository(this).launcherApps()

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
            setPadding(dp(18), dp(18), dp(18), dp(16))
            setBackgroundColor(Color.rgb(8, 12, 20))
        }

        root.addView(TextView(this).apply {
            text = "HERMES DESKTOP 0.2"
            setTextColor(Color.WHITE)
            textSize = 25f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = if (externalDisplayId == null)
                "Sin pantalla externa · conecta USB-C/HDMI o una pantalla secundaria compatible"
            else
                "Pantalla #" + externalDisplayId + " · " + mode.label +
                    " · Accesibilidad " + if (HermesAccessibilityService.isRunning()) "activa" else "inactiva"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modeRow.addView(actionButton("🖥 Escritorio") {
            setMode(DesktopMode.DESKTOP)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        modeRow.addView(actionButton("📺 TV") {
            setMode(DesktopMode.TV)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(modeRow)

        val search = EditText(this).apply {
            hint = "Buscar app en Hermes Desktop…"
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
            dp(54)
        ))

        if (externalDisplayId != null) {
            root.addView(TouchpadView(
                this,
                onNavigate = { dx, dy -> shellView?.navigate(dx, dy) },
                onTap = { shellView?.activateSelection() }
            ), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        } else {
            root.addView(TextView(this).apply {
                text = "El touchpad aparecerá aquí cuando Android exponga una segunda pantalla."
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        navRow.addView(actionButton("← Atrás") {
            systemAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        navRow.addView(actionButton("● Inicio") {
            systemAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        navRow.addView(actionButton("▣ Recientes") {
            if (!HermesAccessibilityService.perform(AccessibilityService.GLOBAL_ACTION_RECENTS)) {
                shellView?.cycleRecent()
                openAccessibilityHelp()
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(navRow)

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolsRow.addView(actionButton("⌨ Teclado") {
            search.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolsRow.addView(actionButton("🤖 Hermes") {
            TermuxBridge.startHermes(this)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolsRow.addView(actionButton(">_ Termux") {
            externalDisplayId?.let { launchPackage("com.termux", it) }
                ?: TermuxBridge.openTermux(this)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(toolsRow)

        val setupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        setupRow.addView(actionButton("♿ Controles sistema") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        setupRow.addView(actionButton("⚙ Android") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(setupRow)

        root.addView(TextView(this).apply {
            val shizuku = if (isPackageInstalled("moe.shizuku.privileged.api")) "detectado" else "no instalado"
            text = "Touchpad: launcher Hermes · Back/Home/Recents: Accesibilidad opcional · Shizuku: " + shizuku
            setTextColor(Color.GRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        return root
    }

    private fun setMode(newMode: DesktopMode) {
        if (mode == newMode) return
        mode = newMode
        if (externalDisplayId != null) {
            attachBestExternalDisplay(force = true)
        } else {
            setContentView(phoneController())
        }
    }

    private fun systemAction(action: Int) {
        if (!HermesAccessibilityService.perform(action)) {
            openAccessibilityHelp()
        }
    }

    private fun openAccessibilityHelp() {
        Toast.makeText(
            this,
            "Activa “Hermes Desktop controls” en Accesibilidad para usar navegación del sistema.",
            Toast.LENGTH_LONG
        ).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun actionButton(textValue: String, action: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            isAllCaps = false
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

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
