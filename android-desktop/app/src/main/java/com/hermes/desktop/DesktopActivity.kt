package com.hermes.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DesktopActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "desktop_mode"
    }

    private lateinit var shellView: DesktopShellView
    private lateinit var apps: List<AppEntry>
    private lateinit var shizuku: ShizukuBridge
    private val handler = Handler(Looper.getMainLooper())
    private val taskWorker = Executors.newSingleThreadExecutor()
    private val taskRefreshInFlight = AtomicBoolean(false)
    private val restoreBounds = mutableMapOf<Int, Rect>()
    private val prefs by lazy { getSharedPreferences("hermes_desktop", MODE_PRIVATE) }

    private var mode = DesktopMode.DESKTOP
    private val recentPackages = mutableListOf<String>()

    private val taskRefresh = object : Runnable {
        override fun run() {
            refreshRunningTasks()
            handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(7, 12, 22)
        window.navigationBarColor = Color.rgb(7, 12, 22)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        apps = AppRepository(this).launcherApps()
        shizuku = ShizukuBridge(this) {
            runOnUiThread { refreshRunningTasks() }
        }
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
            handler.removeCallbacks(taskRefresh)
            handler.post(taskRefresh)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(taskRefresh)
        super.onPause()
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
            },
            onShowDesktop = {
                shellView.requestFocus()
            },
            onTaskAction = { task, action ->
                handleTaskAction(task, action)
            }
        )
        setContentView(shellView)
        shellView.post {
            shellView.requestFocus()
            handler.removeCallbacks(taskRefresh)
            handler.post(taskRefresh)
        }
    }

    private fun refreshRunningTasks() {
        if (!::shellView.isInitialized || !::shizuku.isInitialized || !shizuku.isReady) {
            return
        }
        if (!taskRefreshInFlight.compareAndSet(false, true)) return

        @Suppress("DEPRECATION")
        val displayId = windowManager.defaultDisplay.displayId

        taskWorker.execute {
            val tasks = runCatching {
                val raw = shizuku.listTasks(displayId)
                if (raw.isBlank()) {
                    emptyList()
                } else {
                    DesktopTaskParser.parse(raw, displayId)
                        .filter { it.packageName != packageName }
                        .filter { it.packageName != "com.android.systemui" }
                        .filter { !it.packageName.startsWith("com.samsung.android.desktop") }
                }
            }.getOrDefault(emptyList())

            handler.post {
                taskRefreshInFlight.set(false)
                if (!isFinishing && !isDestroyed && ::shellView.isInitialized) {
                    shellView.updateRunningTasks(tasks)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun handleTaskAction(task: DesktopTask, action: DesktopTaskAction) {
        if (!shizuku.isReady) {
            Toast.makeText(this, "Shizuku requerido para controlar ventanas", Toast.LENGTH_SHORT).show()
            return
        }

        val (width, height) = displaySize()
        val taskbarReserve = dp(92)
        val usableBottom = (height - taskbarReserve).coerceAtLeast(1)

        fun rememberBounds() {
            task.bounds?.let { bounds ->
                if (bounds.width() > 0 && bounds.height() > 0) {
                    restoreBounds[task.taskId] = Rect(bounds)
                }
            }
        }

        when (action) {
            DesktopTaskAction.FOCUS -> {
                shizuku.focusTask(task.taskId)
            }

            DesktopTaskAction.MINIMIZE -> {
                // Android 12 / Samsung has no public minimize primitive available to us here.
                // Until the privileged backend proves a real moveTaskToBack path, bring the
                // Hermes desktop task forward so the target window is genuinely hidden behind it.
                shizuku.focusTask(this@DesktopActivity.taskId)
                shellView.requestFocus()
            }

            DesktopTaskAction.MAXIMIZE -> {
                rememberBounds()
                shizuku.resizeTask(task.taskId, 0, 0, width, usableBottom)
                shizuku.focusTask(task.taskId)
            }

            DesktopTaskAction.RESTORE -> {
                val restore = restoreBounds[task.taskId] ?: Rect(
                    width / 8,
                    height / 10,
                    width * 7 / 8,
                    usableBottom - height / 12
                )
                shizuku.resizeTask(
                    task.taskId,
                    restore.left,
                    restore.top,
                    restore.right,
                    restore.bottom
                )
                shizuku.focusTask(task.taskId)
            }

            DesktopTaskAction.SNAP_LEFT -> {
                rememberBounds()
                shizuku.resizeTask(
                    task.taskId,
                    0,
                    0,
                    width / 2,
                    usableBottom
                )
                shizuku.focusTask(task.taskId)
            }

            DesktopTaskAction.SNAP_RIGHT -> {
                rememberBounds()
                shizuku.resizeTask(
                    task.taskId,
                    width / 2,
                    0,
                    width,
                    usableBottom
                )
                shizuku.focusTask(task.taskId)
            }

            DesktopTaskAction.CLOSE -> {
                shizuku.closeTask(task.taskId)
                handler.postDelayed({ refreshRunningTasks() }, 250)
            }
        }
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
                    (metrics.heightPixels - dp(92)).coerceAtLeast(metrics.heightPixels * 3 / 4)
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

    override fun onDestroy() {
        handler.removeCallbacks(taskRefresh)
        taskWorker.shutdownNow()
        if (::shizuku.isInitialized) {
            shizuku.close()
        }
        super.onDestroy()
    }
}
