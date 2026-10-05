package com.hermes.desktop

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager

class DesktopPresentation(
    outerContext: Context,
    display: Display,
    private val shellFactory: (Context) -> DesktopShellView
) : Presentation(outerContext, display) {

    private var shell: DesktopShellView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window?.decorView?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
        val createdShell = shellFactory(context)
        shell = createdShell
        setContentView(createdShell)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shell?.handleExternalKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        shell = null
        super.onStop()
    }
}
