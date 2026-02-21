package com.kunbox.singbox.receiver

import android.app.AlarmManager
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.kunbox.singbox.manager.VpnServiceManager
import com.kunbox.singbox.repository.LogRepository
import com.kunbox.singbox.repository.SettingsRepository
import com.kunbox.singbox.utils.RootCommandExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 开机广播接收器
 * 在系统启动完成后根据用户设置尝试自动拉起 VPN/代理服务。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_OPLUS_BOOT_COMPLETED = "oplus.intent.action.BOOT_COMPLETED"
        private const val ACTION_OPLUS_BOOT_COMPLETED_ALT = "oplus.intent.action.OPLUS_BOOT_COMPLETED"
        private const val ACTION_OPPO_BOOT_COMPLETED = "oppo.intent.action.BOOT_COMPLETED"
        private const val ACTION_LOCKED_BOOT_COMPLETED = Intent.ACTION_LOCKED_BOOT_COMPLETED
        private const val ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED"
        private const val ACTION_BOOT_AUTOSTART_RETRY = "com.kunbox.singbox.action.BOOT_AUTOSTART_RETRY"
        private const val BOOT_START_DELAY_MS = 2_000L
        private const val ROOT_START_VERIFY_DELAY_MS = 1_200L
        private const val DUPLICATE_GUARD_WINDOW_MS = 15_000L
        private const val RETRY_1_DELAY_MS = 20_000L
        private const val RETRY_2_DELAY_MS = 60_000L
        private const val RETRY_3_DELAY_MS = 180_000L
        private const val RETRY_4_DELAY_MS = 360_000L
        private const val RETRY_1_REQUEST_CODE = 9101
        private const val RETRY_2_REQUEST_CODE = 9102
        private const val RETRY_3_REQUEST_CODE = 9103
        private const val RETRY_4_REQUEST_CODE = 9104
        private const val BOOT_TRACE_FILE_NAME = "boot_autostart.log"
        private const val BOOT_TRACE_MAX_BYTES = 128 * 1024L

        @Volatile
        private var lastStartAttemptElapsedMs: Long = 0L
    }

    private data class ServiceRuntimeState(
        val running: Boolean,
        val starting: Boolean,
        val bgProcessAlive: Boolean
    ) {
        val active: Boolean
            get() = (running || starting) && bgProcessAlive
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!isBootAction(action)) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isRetryAction = action == ACTION_BOOT_AUTOSTART_RETRY
                if (!isRetryAction) {
                    scheduleRetryAlarms(appContext)
                }

                appendBootTrace(appContext, "received action=$action")

                if (!isUserUnlocked(appContext)) {
                    Log.i(TAG, "User is locked, waiting for retry action")
                    appendBootTrace(appContext, "skip action=$action because user locked")
                    return@launch
                }

                val settingsRepository = SettingsRepository.getInstance(appContext)
                settingsRepository.reloadFromStorage()
                val settings = settingsRepository.settings.value
                Log.i(
                    TAG,
                    "Received action=$action, autoStartOnBoot=${settings.autoStartOnBoot}, tunEnabled=${settings.tunEnabled}"
                )
                appendBootTrace(
                    appContext,
                    "loaded settings action=$action autoStartOnBoot=${settings.autoStartOnBoot} tunEnabled=${settings.tunEnabled}"
                )
                runCatching {
                    LogRepository.getInstance().addLog(
                        "INFO BootCompletedReceiver: action=$action autoStartOnBoot=${settings.autoStartOnBoot} tunEnabled=${settings.tunEnabled}"
                    )
                }

                if (!settings.autoStartOnBoot) {
                    Log.i(TAG, "autoStartOnBoot disabled, skip")
                    cancelRetryAlarms(appContext)
                    appendBootTrace(appContext, "skip autoStartOnBoot disabled")
                    runCatching {
                        LogRepository.getInstance().addLog("INFO BootCompletedReceiver: skip autoStartOnBoot disabled")
                    }
                    return@launch
                }

                val state = readServiceRuntimeState(appContext)
                if (state.active) {
                    Log.i(TAG, "VPN already running/starting, skip")
                    cancelRetryAlarms(appContext)
                    appendBootTrace(
                        appContext,
                        "skip active running=${state.running} starting=${state.starting} bgAlive=${state.bgProcessAlive}"
                    )
                    runCatching {
                        LogRepository.getInstance().addLog(
                            "INFO BootCompletedReceiver: skip already active running=${state.running} starting=${state.starting} bgAlive=${state.bgProcessAlive}"
                        )
                    }
                    return@launch
                }
                if (state.running || state.starting) {
                    Log.w(TAG, "Detected stale VPN state without :bg process, continue auto-start")
                    runCatching {
                        LogRepository.getInstance().addLog(
                            "WARN BootCompletedReceiver: stale state running=${state.running} starting=${state.starting} bgAlive=${state.bgProcessAlive}"
                        )
                    }
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastStartAttemptElapsedMs < DUPLICATE_GUARD_WINDOW_MS) {
                    Log.i(TAG, "Duplicate boot trigger within guard window, skip")
                    appendBootTrace(appContext, "skip duplicate trigger action=$action")
                    runCatching {
                        LogRepository.getInstance().addLog("INFO BootCompletedReceiver: skip duplicate trigger")
                    }
                    return@launch
                }
                lastStartAttemptElapsedMs = now

                if (settings.tunEnabled) {
                    val prepareIntent = VpnService.prepare(appContext)
                    if (prepareIntent != null) {
                        Log.w(TAG, "VPN permission not granted, cannot auto-start TUN mode")
                        appendBootTrace(appContext, "skip no vpn permission")
                        runCatching {
                            LogRepository.getInstance().addLog("WARN BootCompletedReceiver: skip VPN permission not granted")
                        }
                        return@launch
                    }
                }

                if (action == Intent.ACTION_BOOT_COMPLETED || action == ACTION_LOCKED_BOOT_COMPLETED ||
                    action == ACTION_OPLUS_BOOT_COMPLETED || action == ACTION_OPLUS_BOOT_COMPLETED_ALT ||
                    action == ACTION_OPPO_BOOT_COMPLETED || action == ACTION_QUICKBOOT_POWERON ||
                    action == ACTION_HTC_QUICKBOOT_POWERON
                ) {
                    delay(BOOT_START_DELAY_MS)
                    val delayedState = readServiceRuntimeState(appContext)
                    if (delayedState.active) {
                        Log.i(TAG, "VPN became running/starting during boot delay, skip")
                        runCatching {
                            LogRepository.getInstance().addLog(
                                "INFO BootCompletedReceiver: skip active during delay running=${delayedState.running} starting=${delayedState.starting} bgAlive=${delayedState.bgProcessAlive}"
                            )
                        }
                        appendBootTrace(
                            appContext,
                            "skip active during delay running=${delayedState.running} starting=${delayedState.starting} bgAlive=${delayedState.bgProcessAlive}"
                        )
                        return@launch
                    }
                }

                val rootStartResult = RootCommandExecutor.startServiceWithRoot(appContext, settings.tunEnabled)
                if (rootStartResult.attempted) {
                    val summary = "success=${rootStartResult.success}, exit=${rootStartResult.exitCode}, timeout=${rootStartResult.timedOut}, out=${rootStartResult.output}"
                    Log.i(TAG, "Root start attempt: $summary")
                    appendBootTrace(appContext, "root start $summary")
                    runCatching {
                        LogRepository.getInstance().addLog("INFO BootCompletedReceiver: root start $summary")
                    }
                }

                if (rootStartResult.success) {
                    delay(ROOT_START_VERIFY_DELAY_MS)
                    val verifyState = readServiceRuntimeState(appContext)
                    if (verifyState.active) {
                        Log.i(TAG, "Auto-start triggered via root, tunEnabled=${settings.tunEnabled}")
                        cancelRetryAlarms(appContext)
                        appendBootTrace(appContext, "auto-start via root tunEnabled=${settings.tunEnabled}")
                        runCatching {
                            LogRepository.getInstance().addLog("INFO BootCompletedReceiver: auto-start via root tunEnabled=${settings.tunEnabled}")
                        }
                        return@launch
                    }
                    Log.w(TAG, "Root command succeeded but service state not running/starting, fallback to normal start")
                    appendBootTrace(appContext, "root sent but state not ready, fallback")
                    runCatching {
                        LogRepository.getInstance().addLog("WARN BootCompletedReceiver: root command sent but state not ready, fallback")
                    }
                }

                VpnServiceManager.startVpn(appContext, settings.tunEnabled)
                Log.i(TAG, "Auto-start triggered on boot, tunEnabled=${settings.tunEnabled}")
                appendBootTrace(appContext, "auto-start fallback api tunEnabled=${settings.tunEnabled}")
                runCatching {
                    LogRepository.getInstance().addLog("INFO BootCompletedReceiver: auto-start triggered tunEnabled=${settings.tunEnabled}")
                }
                delay(1_200L)
                val finalState = readServiceRuntimeState(appContext)
                if (finalState.active) {
                    cancelRetryAlarms(appContext)
                    appendBootTrace(
                        appContext,
                        "final verify running=${finalState.running} starting=${finalState.starting} bgAlive=${finalState.bgProcessAlive}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start on boot", e)
                appendBootTrace(appContext, "failed ${e::class.java.simpleName}: ${e.message}")
                runCatching {
                    LogRepository.getInstance().addLog("ERROR BootCompletedReceiver: failed ${e.message}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isBootAction(action: String): Boolean {
        return action == Intent.ACTION_BOOT_COMPLETED ||
            action == ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_USER_UNLOCKED ||
            action == ACTION_BOOT_AUTOSTART_RETRY ||
            action == ACTION_OPLUS_BOOT_COMPLETED ||
            action == ACTION_OPLUS_BOOT_COMPLETED_ALT ||
            action == ACTION_OPPO_BOOT_COMPLETED ||
            action == ACTION_QUICKBOOT_POWERON ||
            action == ACTION_HTC_QUICKBOOT_POWERON
    }

    private fun isBackgroundProcessAlive(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val bgProcessName = "${context.packageName}:bg"
        return activityManager.runningAppProcesses?.any { it.processName == bgProcessName } == true
    }

    private fun readServiceRuntimeState(context: Context): ServiceRuntimeState {
        return ServiceRuntimeState(
            running = VpnServiceManager.isRunning(context),
            starting = VpnServiceManager.isStarting(),
            bgProcessAlive = isBackgroundProcessAlive(context)
        )
    }

    private fun scheduleRetryAlarms(context: Context) {
        scheduleRetry(context, RETRY_1_REQUEST_CODE, RETRY_1_DELAY_MS)
        scheduleRetry(context, RETRY_2_REQUEST_CODE, RETRY_2_DELAY_MS)
        scheduleRetry(context, RETRY_3_REQUEST_CODE, RETRY_3_DELAY_MS)
        scheduleRetry(context, RETRY_4_REQUEST_CODE, RETRY_4_DELAY_MS)
        appendBootTrace(context, "retry alarms scheduled 20s/60s/180s/360s")
        runCatching {
            LogRepository.getInstance().addLog("INFO BootCompletedReceiver: retry alarms scheduled 20s/60s/180s/360s")
        }
    }

    private fun scheduleRetry(context: Context, requestCode: Int, delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAtMillis = System.currentTimeMillis() + delayMs
        val pendingIntent = buildRetryPendingIntent(
            context,
            requestCode,
            PendingIntent.FLAG_UPDATE_CURRENT
        ) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelRetryAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        listOf(RETRY_1_REQUEST_CODE, RETRY_2_REQUEST_CODE, RETRY_3_REQUEST_CODE, RETRY_4_REQUEST_CODE).forEach { requestCode ->
            val pendingIntent = buildRetryPendingIntent(
                context,
                requestCode,
                PendingIntent.FLAG_NO_CREATE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
        appendBootTrace(context, "retry alarms cancelled")
        runCatching {
            LogRepository.getInstance().addLog("INFO BootCompletedReceiver: retry alarms cancelled")
        }
    }

    private fun buildRetryPendingIntent(
        context: Context,
        requestCode: Int,
        flag: Int
    ): PendingIntent? {
        val intent = Intent(context, BootCompletedReceiver::class.java).apply {
            action = ACTION_BOOT_AUTOSTART_RETRY
            `package` = context.packageName
        }
        val flags = flag or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return true
        return userManager.isUserUnlocked
    }

    private fun appendBootTrace(context: Context, message: String) {
        runCatching {
            val traceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val file = File(traceContext.filesDir, BOOT_TRACE_FILE_NAME)
            if (file.exists() && file.length() > BOOT_TRACE_MAX_BYTES) {
                val tail = file.readLines().takeLast(200)
                file.writeText(tail.joinToString(separator = "\n", postfix = "\n"))
            }
            file.appendText("[${System.currentTimeMillis()}] $message\n")
        }
    }
}
