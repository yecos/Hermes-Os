package com.hermes.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), DisplayManager.DisplayListener {

    private lateinit var displayManager: DisplayManager
    private lateinit var apps: List<AppEntry>
    private var presentation: DesktopPresentation? = null
    private var externalDisplayId: Int? = null

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
    }

    override fun onPause() {
        displayManager.unregisterDisplayListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        presentation?.dismiss()
        presentation = null
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) = attachBestExternalDisplay()

    override fun onDisplayRemoved(displayId: Int) {
        if (externalDisplayId == displayId) {
            presentation?.dismiss()
            presentation = null
            externalDisplayId = null
            setContentView(phoneController())
        }
    }

    override fun onDisplayChanged(displayId: Int) = Unit

    @Suppress("DEPRECATION")
    private fun attachBestExternalDisplay() {
        val primaryId = windowManager.defaultDisplay.displayId
        val target = displayManager.displays
            .firstOrNull { it.displayId != primaryId && it.state == Display.STATE_ON }
            ?: return

        if (externalDisplayId == target.displayId && presentation?.isShowing == true) return

        presentation?.dismiss()
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
            onLaunchApp = { launchApp(it, displayId) },
            onOpenTermux = { launchPackage("com.termux", displayId) },
            onStartHermes = { TermuxBridge.startHermes(this) }
        )

    private fun phoneController(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(8, 12, 20))

            addView(TextView(this@MainActivity).apply {
                text = "HERMES DESKTOP"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            })

            addView(TextView(this@MainActivity).apply {
                text = if (externalDisplayId == null)
                    "Conecta una pantalla externa por USB-C/HDMI o usa un dispositivo compatible con pantalla secundaria."
                else
                    "Escritorio activo en pantalla #" + externalDisplayId
                setTextColor(Color.LTGRAY)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(24))
            })

            addView(actionButton("🤖 Iniciar Hermes") {
                TermuxBridge.startHermes(this@MainActivity)
            })
            addView(actionButton(">_ Abrir Termux") {
                externalDisplayId?.let { launchPackage("com.termux", it) }
                    ?: TermuxBridge.openTermux(this@MainActivity)
            })
            addView(actionButton("⚙ Ajustes de Android") {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            })

            addView(TextView(this@MainActivity).apply {
                text = "MVP 0.1 · Siguiente: touchpad, ventanas avanzadas, TV mode y Shizuku/Accessibility opcional."
                setTextColor(Color.GRAY)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, 0)
            })
        }
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
            Toast.makeText(this, "Android bloqueó el lanzamiento en esa pantalla", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
