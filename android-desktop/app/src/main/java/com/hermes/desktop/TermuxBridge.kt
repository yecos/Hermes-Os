package com.hermes.desktop

import android.content.Context
import android.content.Intent
import android.widget.Toast

object TermuxBridge {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    fun openTermux(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (launch == null) {
            Toast.makeText(context, "Termux no está instalado", Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }

    fun startHermes(context: Context) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", "hermes"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }

        runCatching { context.startService(intent) }
            .onSuccess {
                Toast.makeText(context, "Hermes enviado a Termux", Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                Toast.makeText(
                    context,
                    "Activa allow-external-apps=true en Termux y concede RUN_COMMAND",
                    Toast.LENGTH_LONG
                ).show()
            }
    }
}
