package com.hermes.desktop

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Window
import android.view.WindowManager

class DesktopPresentation(
    outerContext: Context,
    display: Display,
    private val shellFactory: () -> DesktopShellView
) : Presentation(outerContext, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(shellFactory())
    }
}
