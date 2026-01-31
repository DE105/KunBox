package com.kunk.singbox.service.manager

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.utils.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ForegroundAppMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ForegroundAppMonitor"
        private const val CHECK_INTERVAL_MS = 300L
        private const val STARTUP_DELAY_MS = 2000L
        private const val COOLDOWN_MS = 3000L
    }

    interface Callbacks {
        fun isVpnRunning(): Boolean
        fun isAppInVpnWhitelist(packageName: String): Boolean
        fun isCoreReady(): Boolean
    }

    private var callbacks: Callbacks? = null
    private var monitorJob: Job? = null
    private var usageStatsManager: UsageStatsManager? = null

    @Volatile
    private var isEnabled: Boolean = false

    @Volatile
    private var hasUsageStatsPermission: Boolean = false

    private var lastForegroundPackage: String = ""
    private val lastResetTimeMap = mutableMapOf<String, Long>()

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
        usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        hasUsageStatsPermission = PermissionUtils.hasUsageStatsPermission(context)
    }

    fun start() {
        if (monitorJob != null) return
        if (usageStatsManager == null || !hasUsageStatsPermission) return

        isEnabled = true
        monitorJob = scope.launch(Dispatchers.IO) {
            delay(STARTUP_DELAY_MS)
            while (isActive && isEnabled) {
                try {
                    checkForegroundApp()
                } catch (_: Exception) {
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        isEnabled = false
        monitorJob?.cancel()
        monitorJob = null
        lastForegroundPackage = ""
        lastResetTimeMap.clear()
    }

    private fun checkForegroundApp() {
        val cb = callbacks ?: return
        if (!cb.isVpnRunning() || !cb.isCoreReady()) return

        val foregroundPackage = getForegroundPackage() ?: return

        if (foregroundPackage == lastForegroundPackage) return

        val previousPackage = lastForegroundPackage
        lastForegroundPackage = foregroundPackage

        if (!cb.isAppInVpnWhitelist(foregroundPackage)) return
        if (previousPackage.isEmpty()) return

        val now = System.currentTimeMillis()
        val lastResetTime = lastResetTimeMap[foregroundPackage] ?: 0L
        if (now - lastResetTime < COOLDOWN_MS) return

        val closedCount = try {
            BoxWrapperManager.closeConnectionsForApp(foregroundPackage)
        } catch (_: Exception) {
            0
        }

        if (closedCount > 0) {
            lastResetTimeMap[foregroundPackage] = now
            Log.i(TAG, "Switched to $foregroundPackage, reset $closedCount connections")
        }
    }

    @Suppress("NestedBlockDepth")
    private fun getForegroundPackage(): String? {
        val usm = usageStatsManager ?: return null
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 5000

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val events = usm.queryEvents(beginTime, endTime)
                var lastPackage: String? = null
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        lastPackage = event.packageName
                    }
                }
                lastPackage
            } else {
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    beginTime,
                    endTime
                )
                stats?.maxByOrNull { it.lastTimeUsed }?.packageName
            }
        } catch (_: SecurityException) {
            null
        }
    }

    fun cleanup() {
        stop()
        callbacks = null
        usageStatsManager = null
        hasUsageStatsPermission = false
    }
}
