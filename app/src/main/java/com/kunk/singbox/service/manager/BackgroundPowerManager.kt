package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台省电管理器
 *
 * 功能:
 * 1. 检测用户离开 App (后台或息屏) 超过指定时间后，关闭非核心进程以节省资源
 * 2. 用户返回 (前台或亮屏) 时立即恢复所有进程
 *
 * 双信号源设计:
 * - 信号1: 主进程通过 IPC 通知 App 前后台变化 (onAppBackground/onAppForeground)
 * - 信号2: ScreenStateManager 通知屏幕开关状态 (onScreenOff/onScreenOn)
 *
 * 省电触发逻辑:
 * - 任一信号表明"用户离开" -> 开始计时
 * - 任一信号表明"用户返回" -> 取消计时并退出省电
 *
 * 设计理念:
 * - VPN 核心连接必须始终保持 (BoxService, TUN 接口)
 * - 非核心进程 (日志收集、连接追踪、流量监控) 可以暂停
 * - 健康检查可以降低频率而非完全停止
 */
class BackgroundPowerManager(
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "BackgroundPowerManager"

        /** 默认后台省电阈值: 30 分钟 */
        const val DEFAULT_BACKGROUND_THRESHOLD_MS = 30 * 60 * 1000L

        /** 最小阈值: 5 分钟 (防止过于激进) */
        const val MIN_THRESHOLD_MS = 5 * 60 * 1000L

        /** 最大阈值: 2 小时 */
        const val MAX_THRESHOLD_MS = 2 * 60 * 60 * 1000L

        // ==================== 后台恢复阈值 (解决 TG 加载问题) ====================

        /** 后台超过此时间后回前台，触发 NetworkBump (TCP 协议，0 秒阈值) */
        // 2025-fix-v17: 参考 v2rayNG，每次前台恢复都刷新底层网络
        const val BACKGROUND_BUMP_THRESHOLD_MS = 0L

        /** QUIC 协议专用阈值：后台超过 30 秒才触发 QUIC Recovery */
        // 2025-fix-v28: QUIC Recovery 会关闭所有连接，太激进
        // 短时间后台（如应用切换）不应触发，否则会破坏正常工作的连接
        const val BACKGROUND_QUIC_RECOVERY_THRESHOLD_MS = 30 * 1000L

        /** 后台超过此时间后回前台，触发完整恢复 (5分钟) */
        const val BACKGROUND_FULL_RECOVERY_THRESHOLD_MS = 5 * 60 * 1000L

        /** 前台恢复防抖时间 (改为 2 秒，更快响应) */
        // 2025-fix-v17: 从 5 秒缩短到 2 秒，加快恢复速度
        const val FOREGROUND_RECOVERY_DEBOUNCE_MS = 2_000L
    }

    /**
     * 省电模式状态
     */
    enum class PowerMode {
        /** 正常模式 - 所有进程运行 */
        NORMAL,
        /** 省电模式 - 非核心进程已暂停 */
        POWER_SAVING
    }

    /**
     * 回调接口 - 由 SingBoxService 实现
     */
    interface Callbacks {
        /** VPN 是否正在运行 */
        val isVpnRunning: Boolean

        /** 暂停非核心进程 (进入省电模式) */
        fun suspendNonEssentialProcesses()

        /** 恢复非核心进程 (退出省电模式) */
        fun resumeNonEssentialProcesses()

        /** 触发 NetworkBump (短暂改变底层网络，强制应用重建连接) */
        fun triggerNetworkBump(reason: String)

        /** 触发完整网络恢复 */
        fun triggerFullRecovery(reason: String)

        /** 触发 QUIC 专用恢复 (Hysteria2/TUIC) */
        fun triggerQUICRecovery(reason: String)
    }

    private var callbacks: Callbacks? = null
    private var backgroundThresholdMs: Long = DEFAULT_BACKGROUND_THRESHOLD_MS
    private var powerSavingJob: Job? = null

    @Volatile
    private var currentMode: PowerMode = PowerMode.NORMAL

    @Volatile
    private var userAwayAtMs: Long = 0L

    // 双信号状态
    @Volatile
    private var isAppInBackground: Boolean = false
    @Volatile
    private var isScreenOff: Boolean = false

    @Volatile
    private var backgroundStartTimeMs: Long = 0L
    @Volatile
    private var lastForegroundRecoveryAtMs: Long = 0L

    /**
     * 当前省电模式
     */
    val powerMode: PowerMode get() = currentMode

    /**
     * 是否处于省电模式
     */
    val isPowerSaving: Boolean get() = currentMode == PowerMode.POWER_SAVING

    /**
     * 用户是否离开 (后台或息屏)
     */
    private val isUserAway: Boolean get() = isAppInBackground || isScreenOff

    /**
     * 初始化管理器
     */
    fun init(callbacks: Callbacks, thresholdMs: Long = DEFAULT_BACKGROUND_THRESHOLD_MS) {
        this.callbacks = callbacks
        this.backgroundThresholdMs = if (thresholdMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            thresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
        }
        val thresholdDisplay = if (backgroundThresholdMs == Long.MAX_VALUE) "NEVER" else "${backgroundThresholdMs / 1000 / 60}min"
        Log.i(TAG, "BackgroundPowerManager initialized (threshold=$thresholdDisplay)")
    }

    /**
     * 更新后台省电阈值
     */
    fun setThreshold(thresholdMs: Long) {
        backgroundThresholdMs = if (thresholdMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            thresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
        }
        Log.i(TAG, "Threshold updated to ${backgroundThresholdMs / 1000 / 60}min")

        // 如果用户已离开，重新计算定时器
        if (isUserAway && currentMode == PowerMode.NORMAL) {
            schedulePowerSavingCheck()
        }
    }

    // ==================== 信号1: 主进程 IPC 通知 ====================

    /**
     * App 进入后台 (来自主进程 IPC)
     */
    fun onAppBackground() {
        if (isAppInBackground) return
        isAppInBackground = true
        backgroundStartTimeMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "[IPC] App entered background at $backgroundStartTimeMs")
        evaluateUserPresence()
    }

    /**
     * App 返回前台 (来自主进程 IPC)
     * 根据后台时长决定是否触发网络恢复
     */
    fun onAppForeground() {
        if (!isAppInBackground) return
        isAppInBackground = false

        val backgroundDuration = if (backgroundStartTimeMs > 0) {
            SystemClock.elapsedRealtime() - backgroundStartTimeMs
        } else 0L

        Log.i(TAG, "[IPC] App returned to foreground after ${backgroundDuration / 1000}s")

        triggerForegroundRecoveryIfNeeded(backgroundDuration, "app_foreground")

        backgroundStartTimeMs = 0L
        evaluateUserPresence()
    }

    // ==================== 信号2: 屏幕状态 ====================

    /**
     * 屏幕关闭 (来自 ScreenStateManager)
     */
    fun onScreenOff() {
        if (isScreenOff) return
        isScreenOff = true
        Log.i(TAG, "[Screen] Screen turned OFF")
        evaluateUserPresence()
    }

    /**
     * 屏幕点亮 (来自 ScreenStateManager)
     */
    fun onScreenOn() {
        if (!isScreenOff) return
        isScreenOff = false

        // 2025-fix-v25: 使用 userAwayAtMs 而不是 backgroundStartTimeMs
        // 因为 backgroundStartTimeMs 只在 App 进入后台时设置
        // 而 userAwayAtMs 在息屏时也会设置
        // 这修复了息屏后恢复时 duration=0 导致不触发 QUIC 恢复的 bug
        val awayDuration = if (userAwayAtMs > 0) {
            SystemClock.elapsedRealtime() - userAwayAtMs
        } else 0L

        Log.i(TAG, "[Screen] Screen turned ON after ${awayDuration / 1000}s away")

        triggerForegroundRecoveryIfNeeded(awayDuration, "screen_on")

        evaluateUserPresence()
    }

    // ==================== 后台恢复逻辑 (解决 TG 加载问题) ====================

    private fun triggerForegroundRecoveryIfNeeded(backgroundDurationMs: Long, source: String) {
        if (callbacks?.isVpnRunning != true) {
            Log.d(TAG, "[$source] VPN not running, skip recovery")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundRecoveryAtMs < FOREGROUND_RECOVERY_DEBOUNCE_MS) {
            Log.d(TAG, "[$source] Recovery skipped (debounce)")
            return
        }

        val isQUIC = BoxWrapperManager.isCurrentOutboundQUICBased()

        when {
            backgroundDurationMs > BACKGROUND_FULL_RECOVERY_THRESHOLD_MS -> {
                Log.i(
                    TAG,
                    "[$source] Long background (${backgroundDurationMs / 1000}s > 5min), " +
                        "triggering full recovery (isQUIC=$isQUIC)"
                )
                lastForegroundRecoveryAtMs = now
                if (isQUIC) {
                    callbacks?.triggerQUICRecovery("${source}_${backgroundDurationMs / 1000}s")
                } else {
                    callbacks?.triggerFullRecovery("${source}_${backgroundDurationMs / 1000}s")
                }
            }
            // 2025-fix-v28: QUIC 协议使用单独的阈值 (30秒)
            // 短时间后台不触发 QUIC Recovery，避免破坏正常连接
            isQUIC && backgroundDurationMs > BACKGROUND_QUIC_RECOVERY_THRESHOLD_MS -> {
                Log.i(
                    TAG,
                    "[$source] QUIC background (${backgroundDurationMs / 1000}s > 30s), " +
                        "triggering QUIC recovery"
                )
                lastForegroundRecoveryAtMs = now
                callbacks?.triggerQUICRecovery("${source}_${backgroundDurationMs / 1000}s")
            }
            // TCP 协议使用 0 阈值，任何后台都触发温和的 NetworkBump
            !isQUIC && backgroundDurationMs > BACKGROUND_BUMP_THRESHOLD_MS -> {
                Log.i(
                    TAG,
                    "[$source] TCP background (${backgroundDurationMs / 1000}s), " +
                        "triggering network bump"
                )
                lastForegroundRecoveryAtMs = now
                callbacks?.triggerNetworkBump("${source}_${backgroundDurationMs / 1000}s")
            }
            else -> {
                Log.d(
                    TAG,
                    "[$source] Short background (${backgroundDurationMs / 1000}s), " +
                        "no recovery needed (isQUIC=$isQUIC)"
                )
            }
        }
    }

    // ==================== 统一判断逻辑 ====================

    /**
     * 评估用户状态并决定是否触发省电
     */
    private fun evaluateUserPresence() {
        if (backgroundThresholdMs == Long.MAX_VALUE) {
            Log.d(TAG, "Power saving disabled (threshold=NEVER)")
            return
        }

        if (isUserAway) {
            // 用户离开 - 开始计时
            if (userAwayAtMs == 0L) {
                userAwayAtMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "User away (background=$isAppInBackground, screenOff=$isScreenOff), scheduling power saving in ${backgroundThresholdMs / 1000 / 60}min")
                schedulePowerSavingCheck()
            }
        } else {
            // 用户回来 - 取消计时并恢复
            val wasAway = userAwayAtMs > 0
            if (wasAway) {
                val awayDuration = SystemClock.elapsedRealtime() - userAwayAtMs
                Log.i(TAG, "User returned after ${awayDuration / 1000}s")
            }
            userAwayAtMs = 0L

            // 取消待执行的省电检查
            powerSavingJob?.cancel()
            powerSavingJob = null

            // 如果处于省电模式，立即恢复
            if (currentMode == PowerMode.POWER_SAVING) {
                exitPowerSavingMode()
            }
        }
    }

    /**
     * 调度省电检查
     */
    private fun schedulePowerSavingCheck() {
        powerSavingJob?.cancel()

        powerSavingJob = serviceScope.launch(Dispatchers.Main) {
            val alreadyAway = SystemClock.elapsedRealtime() - userAwayAtMs
            val remainingDelay = (backgroundThresholdMs - alreadyAway).coerceAtLeast(0L)

            if (remainingDelay > 0) {
                Log.d(TAG, "Waiting ${remainingDelay / 1000}s before entering power saving mode")
                delay(remainingDelay)
            }

            // 再次检查是否仍然离开
            if (isUserAway && callbacks?.isVpnRunning == true) {
                enterPowerSavingMode()
            }
        }
    }

    /**
     * 进入省电模式
     */
    private fun enterPowerSavingMode() {
        if (currentMode == PowerMode.POWER_SAVING) return

        Log.i(TAG, ">>> Entering POWER_SAVING mode - suspending non-essential processes")
        currentMode = PowerMode.POWER_SAVING

        try {
            callbacks?.suspendNonEssentialProcesses()
            Log.i(TAG, "Non-essential processes suspended successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to suspend non-essential processes", e)
        }
    }

    /**
     * 退出省电模式
     */
    private fun exitPowerSavingMode() {
        if (currentMode == PowerMode.NORMAL) return

        Log.i(TAG, "<<< Exiting POWER_SAVING mode - resuming all processes")
        currentMode = PowerMode.NORMAL

        try {
            callbacks?.resumeNonEssentialProcesses()
            Log.i(TAG, "All processes resumed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume processes", e)
        }
    }

    /**
     * 强制进入省电模式 (用于测试或手动触发)
     */
    fun forceEnterPowerSaving() {
        if (callbacks?.isVpnRunning == true) {
            enterPowerSavingMode()
        }
    }

    /**
     * 强制退出省电模式
     */
    fun forceExitPowerSaving() {
        exitPowerSavingMode()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        powerSavingJob?.cancel()
        powerSavingJob = null
        currentMode = PowerMode.NORMAL
        isAppInBackground = false
        isScreenOff = false
        userAwayAtMs = 0L
        backgroundStartTimeMs = 0L
        lastForegroundRecoveryAtMs = 0L
        callbacks = null
        Log.i(TAG, "BackgroundPowerManager cleaned up")
    }

    /**
     * 获取统计信息 (用于调试)
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "currentMode" to currentMode.name,
            "isAppInBackground" to isAppInBackground,
            "isScreenOff" to isScreenOff,
            "isUserAway" to isUserAway,
            "thresholdMin" to (backgroundThresholdMs / 1000 / 60),
            "awayDurationSec" to if (userAwayAtMs > 0) {
                (SystemClock.elapsedRealtime() - userAwayAtMs) / 1000
            } else 0L,
            "backgroundDurationSec" to if (backgroundStartTimeMs > 0) {
                (SystemClock.elapsedRealtime() - backgroundStartTimeMs) / 1000
            } else 0L
        )
    }
}
