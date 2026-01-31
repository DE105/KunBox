package com.kunk.singbox.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService for real-time foreground app detection.
 * Broadcasts foreground app changes to VPN service for connection refresh.
 */
class ForegroundAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_FOREGROUND_APP_CHANGED = "com.kunk.singbox.FOREGROUND_APP_CHANGED"
        const val EXTRA_PACKAGE_NAME = "package_name"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var lastPackageName: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isEmpty() || packageName == lastPackageName) return
        if (packageName == "com.kunk.singbox") return

        lastPackageName = packageName

        val intent = Intent(ACTION_FOREGROUND_APP_CHANGED).apply {
            setPackage(packageName())
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        // Required override, no action needed
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun packageName(): String = applicationContext.packageName
}
