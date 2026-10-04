package com.hermes.desktop

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

class ShizukuBridge(
    private val context: Context,
    private val onStateChanged: () -> Unit
) {

    companion object {
        private const val REQUEST_CODE = 7303
    }

    private var service: IPrivilegedBridge? = null
    private var lastError: String? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context, PrivilegedUserService::class.java)
    )
        .processNameSuffix("privileged")
        .daemon(false)
        .version(3)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPrivilegedBridge.Stub.asInterface(binder)
            lastError = null
            onStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            onStateChanged()
        }
    }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            lastError = null
            connectIfPossible(requestPermission = false)
            onStateChanged()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            service = null
            lastError = "Shizuku se detuvo"
            onStateChanged()
        }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    connectIfPossible(requestPermission = false)
                } else {
                    lastError = "Permiso Shizuku denegado"
                }
                onStateChanged()
            }
        }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            connectIfPossible(requestPermission = false)
        }
    }

    val isReady: Boolean
        get() = service != null

    fun stateLabel(): String {
        if (service != null) {
            val remote = runCatching { service?.status() }.getOrNull()
            return "Shizuku listo" + if (remote.isNullOrBlank()) "" else " · $remote"
        }

        if (!isManagerInstalled()) return "Shizuku no instalado"
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return "Shizuku apagado"

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        return when {
            granted -> lastError ?: "Shizuku autorizado · conectando"
            lastError != null -> lastError!!
            else -> "Shizuku sin autorización"
        }
    }

    fun requestOrConnect() {
        if (!isManagerInstalled()) {
            lastError = "Instala Shizuku primero"
            onStateChanged()
            return
        }

        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            lastError = "Abre Shizuku e inicia el servicio"
            onStateChanged()
            return
        }

        connectIfPossible(requestPermission = true)
    }

    private fun connectIfPossible(requestPermission: Boolean) {
        if (service != null) return

        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            lastError = "Shizuku v11+ requerido"
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!granted) {
            if (requestPermission) {
                runCatching {
                    if (!Shizuku.shouldShowRequestPermissionRationale()) {
                        Shizuku.requestPermission(REQUEST_CODE)
                    } else {
                        lastError = "Autoriza Hermes Desktop desde Shizuku"
                    }
                }.onFailure {
                    lastError = it.message ?: "No se pudo solicitar permiso"
                }
            }
            return
        }

        runCatching {
            Shizuku.bindUserService(args, connection)
        }.onFailure {
            lastError = it.message ?: "No se pudo enlazar UserService"
        }
    }

    fun movePointer(displayId: Int, x: Float, y: Float, dragging: Boolean): Boolean =
        runCatching {
            service?.movePointer(displayId, x, y, dragging) == true
        }.getOrDefault(false)

    fun pointerButton(displayId: Int, x: Float, y: Float, down: Boolean): Boolean =
        runCatching {
            service?.pointerButton(displayId, x, y, down) == true
        }.getOrDefault(false)

    fun click(displayId: Int, x: Float, y: Float): Boolean =
        runCatching {
            service?.click(displayId, x, y) == true
        }.getOrDefault(false)

    fun scroll(displayId: Int, x: Float, y: Float, delta: Float): Boolean =
        runCatching {
            service?.scroll(displayId, x, y, delta) == true
        }.getOrDefault(false)

    fun key(displayId: Int, keyCode: Int): Boolean =
        runCatching {
            service?.keyEvent(displayId, keyCode) == true
        }.getOrDefault(false)

    fun keyCombination(displayId: Int, vararg keyCodes: Int): Boolean =
        runCatching {
            service?.keyCombination(displayId, keyCodes) == true
        }.getOrDefault(false)

    fun startVirtualMouse(): Boolean =
        runCatching { service?.startVirtualMouse() == true }.getOrDefault(false)

    fun virtualMouseMove(dx: Float, dy: Float): Boolean =
        runCatching { service?.virtualMouseMove(dx, dy) == true }.getOrDefault(false)

    fun virtualMouseButton(button: Int, down: Boolean): Boolean =
        runCatching { service?.virtualMouseButton(button, down) == true }.getOrDefault(false)

    fun virtualMouseClick(button: Int): Boolean =
        runCatching { service?.virtualMouseClick(button) == true }.getOrDefault(false)

    fun virtualMouseScroll(vertical: Int, horizontal: Int = 0): Boolean =
        runCatching { service?.virtualMouseScroll(vertical, horizontal) == true }.getOrDefault(false)

    fun stopVirtualMouse(): Boolean =
        runCatching { service?.stopVirtualMouse() == true }.getOrDefault(false)

    fun close() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)

        runCatching {
            if (service != null && Shizuku.pingBinder()) {
                Shizuku.unbindUserService(args, connection, false)
            }
        }
        service = null
    }

    @Suppress("DEPRECATION")
    private fun isManagerInstalled(): Boolean =
        runCatching {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        }.getOrDefault(false)
}
