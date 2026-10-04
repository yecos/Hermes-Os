package com.hermes.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast

class DesktopActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "desktop_mode"
    }

    private lateinit var shellView: DesktopShellView
    private lateinit var apps: List<AppEntry>
    private val prefs by lazy { getSharedPreferences("hermes_desktop", MODE_PRIVATE) }

    private var mode = DesktopMode.DESKTOP
    private val recentPackages = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(7, 12, 22)
        window.navigationBarColor = Color.rgb(7, 12, 22)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        apps = AppRepository(this).launcherApps()
        restoreState()
        applyIntent(intent)
        renderDesktop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        applyIntent(intent)
        if (::shellView.isInitialized) {
            shellView.setMode(mode)
            shellView.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::shellView.isInitialized) {
            shellView.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::shellView.isInitialized && shellView.handleExternalKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun restoreState() {
        recentPackages.clear()
        recentPackages += prefs.getString("recent_packages", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .take(5)

        mode = if (prefs.getString("desktop_mode", "desktop") == "tv") {
            DesktopMode.TV
        } else {
            DesktopMode.DESKTOP
        }
    }

    private fun applyIntent(intent: Intent) {
        mode = when (intent.getStringExtra(EXTRA_MODE)) {
            "tv" -> DesktopMode.TV
            "desktop" -> DesktopMode.DESKTOP
            else -> mode
        }
    }

    private fun renderDesktop() {
        shellView = DesktopShellView(
            context = this,
            apps = apps,
            initialMode = mode,
            initialRecents = recentPackages.mapNotNull { pkg ->
                apps.firstOrNull { it.packageName == pkg }
            },
            onLaunchApp = { app ->
                rememberRecent(app)
                launchPackageOnThisDisplay(app.packageName)
            },
            onOpenTermux = {
                launchPackageOnThisDisplay("com.termux")
            },
            onStartHermes = {
                TermuxBridge.startHermes(this)
            }
        )
        setContentView(shellView)
        shellView.post { shellView.requestFocus() }
    }

    private fun rememberRecent(app: AppEntry) {
        recentPackages.remove(app.packageName)
        recentPackages.add(0, app.packageName)
        while (recentPackages.size > 5) {
            recentPackages.removeAt(recentPackages.lastIndex)
        }
        prefs.edit()
            .putString("recent_packages", recentPackages.joinToString("|"))
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun launchPackageOnThisDisplay(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "No se pudo abrir $packageName", Toast.LENGTH_SHORT).show()
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val displayId = windowManager.defaultDisplay.displayId
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                launchBounds = Rect(
                    metrics.widthPixels / 12,
                    metrics.heightPixels / 10,
                    metrics.widthPixels * 11 / 12,
                    metrics.heightPixels * 9 / 10
                )
            }
        }

        runCatching {
            startActivity(launchIntent, options.toBundle())
        }.recoverCatching {
            startActivity(launchIntent)
        }.onFailure {
            Toast.makeText(
                this,
                "Android bloqueó el lanzamiento en esta pantalla",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
