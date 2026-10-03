package com.hermes.desktop

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class HermesAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var current: HermesAccessibilityService? = null

        fun isRunning(): Boolean = current != null

        fun perform(action: Int): Boolean =
            current?.performGlobalAction(action) == true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
