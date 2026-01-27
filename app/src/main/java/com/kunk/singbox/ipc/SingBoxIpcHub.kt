package com.kunk.singbox.ipc

import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import java.util.concurrent.atomic.AtomicLong

object SingBoxIpcHub {
    private const val TAG = "SingBoxIpcHub"

    private val logRepo by lazy { LogRepository.getInstance() }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logRepo.addLog("INFO [IPC] $msg")
    }

    @Volatile
    private var stateOrdinal: Int = ServiceState.STOPPED.ordinal

    @Volatile
    private var activeLabel: String = ""

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var manuallyStopped: Boolean = false

    private val callbacks = RemoteCallbackList<ISingBoxServiceCallback>()

    private val broadcastLock = Any()
    @Volatile private var broadcasting: Boolean = false
    @Volatile private var broadcastPending: Boolean = false

    // 省电管理器引用，由 SingBoxService 设置
    @Volatile
    private var powerManager: BackgroundPowerManager? = null

    // Foreground recovery handler (set by SingBoxService) to avoid calling libbox concurrently.
    @Volatile
    private var foregroundRecoveryHandler: (() -> Unit)? = null

    // 2025-fix-v6: 状态更新时间戳，用于检测回调通道是否正常
    private val lastStateUpdateAtMs = AtomicLong(0L)

    // 2025-fix-v7: 上次应用返回前台的时间戳，用于防抖
    private val lastForegroundAtMs = AtomicLong(0L)

    // 2025-fix-v11: 上次应用进入后台的时间戳，用于计算后台时长
    private val lastBackgroundAtMs = AtomicLong(0L)

    // 2025-fix-v11: 前台恢复防抖最小间隔 (2秒，从5秒缩短)
    // 快速切换前后台时跳过恢复，避免恢复风暴
    private const val FOREGROUND_RESET_DEBOUNCE_MS = 2_000L

    // 2025-fix-v11: 智能恢复模式阈值
    // 根据后台时长选择恢复激进程度，比其他代理软件更智能
    private const val BACKGROUND_QUICK_THRESHOLD_MS = 30_000L // <30秒: QUICK模式
    private const val BACKGROUND_FULL_THRESHOLD_MS = 5 * 60_000L // <5分钟: FULL模式
    // >5分钟: DEEP模式

    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

    fun setForegroundRecoveryHandler(handler: (() -> Unit)?) {
        foregroundRecoveryHandler = handler
        Log.d(TAG, "ForegroundRecoveryHandler ${if (handler != null) "set" else "cleared"}")
    }

    /**
     * 接收主进程的 App 生命周期通知
     *
     * 2025-fix-v11: 完全重写前台恢复逻辑，解决 "后台恢复后 TG 等应用一直加载中" 问题
     *
     * 根因: CheckNetworkRecoveryNeeded() 只返回 IsPaused()，导致大多数情况下跳过恢复
     * 但 TCP 连接可能因服务器超时、NAT超时、网络切换等原因失效，即使 VPN 未暂停
     *
     * 修复方案:
     * 1. 删除错误的 isNetworkRecoveryNeeded() 条件检查
     * 2. 添加智能恢复模式: 根据后台时长选择恢复激进程度
     * 3. 保留防抖机制: 避免快速前后台切换时的恢复风暴
     *
     * 恢复模式:
     * - QUICK (后台<30秒): 仅关闭闲置连接，轻量恢复
     * - FULL (后台30秒-5分钟): 完整恢复，重置网络栈
     * - DEEP (后台>5分钟): 最激进恢复，包含网络探测
     */
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    fun onAppLifecycle(isForeground: Boolean) {
        val vpnState = ServiceState.values().getOrNull(stateOrdinal)?.name ?: "UNKNOWN"
        log("onAppLifecycle: isForeground=$isForeground, vpnState=$vpnState")

        if (isForeground) {
            powerManager?.onAppForeground()
            performForegroundRecovery()
        } else {
            lastBackgroundAtMs.set(SystemClock.elapsedRealtime())
            powerManager?.onAppBackground()
        }
    }

    /**
     * 2025-fix-v11: 前台恢复核心逻辑
     *
     * 设计原则:
     * 1. 永不猜测，始终恢复 - 当用户从后台返回时，确保连接是新鲜的
     * 2. 最小干扰 - 不重启 VPN 服务，只重置连接
     * 3. 智能选择 - 根据后台时长选择恢复激进程度
     */
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun performForegroundRecovery() {
        val isVpnRunning = stateOrdinal == ServiceState.RUNNING.ordinal
        if (!isVpnRunning) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val lastForeground = lastForegroundAtMs.get()
        val timeSinceLastForeground = now - lastForeground

        if (timeSinceLastForeground < FOREGROUND_RESET_DEBOUNCE_MS) {
            Log.d(TAG, "[Foreground] skipped (debounce, elapsed=${timeSinceLastForeground}ms)")
            return
        }

        val backgroundDuration = now - lastBackgroundAtMs.get()
        val recoveryMode = selectRecoveryMode(backgroundDuration)

        if (recoveryMode == null) {
            Log.d(TAG, "[Foreground] skipped (background too short: ${backgroundDuration}ms)")
            return
        }

        log("[Foreground] VPN running, background=${backgroundDuration}ms, mode=$recoveryMode")

        val recoverySuccess = executeRecovery(recoveryMode)

        if (recoverySuccess) {
            lastForegroundAtMs.set(now)
            log("[Foreground] recovery success ($recoveryMode)")
        } else {
            Log.w(TAG, "[Foreground] recovery failed, will retry on next foreground event")
        }
    }

    /**
     * 2025-fix-v11: 根据后台时长智能选择恢复模式
     * 这比其他代理软件更智能 - 它们都是无脑全量恢复
     *
     * 2025-fix-v12: 修复 lastBackgroundAtMs=0 导致首次前台误触发 DEEP 恢复
     * 当应用首次启动时 lastBackgroundAtMs 为 0，导致 backgroundDuration 变成巨大数字，
     * 错误触发 DEEP 模式重置 DNS 缓存，造成连接卡住。
     */
    private fun selectRecoveryMode(backgroundDurationMs: Long): RecoveryMode? {
        // 2025-fix-v12: 如果从未进入过后台，不需要恢复
        // lastBackgroundAtMs = 0 表示应用启动后从未进入后台
        if (lastBackgroundAtMs.get() == 0L) {
            return null
        }

        return when {
            backgroundDurationMs < FOREGROUND_RESET_DEBOUNCE_MS -> null
            backgroundDurationMs < BACKGROUND_QUICK_THRESHOLD_MS -> RecoveryMode.QUICK
            backgroundDurationMs < BACKGROUND_FULL_THRESHOLD_MS -> RecoveryMode.FULL
            else -> RecoveryMode.DEEP
        }
    }

    /**
     * 2025-fix-v13: 执行恢复操作 (同步版本)
     *
     * 关键改进:
     * 1. 不再使用 foregroundRecoveryHandler (异步，无法等待完成)
     * 2. 直接调用 BoxWrapperManager 同步方法
     * 3. 所有模式都强制 resetAllConnections，解决 TG 等应用卡在旧连接的问题
     *
     * 根因: 后台一段时间后，服务器端可能已经关闭了 TCP 连接，
     * 但客户端 TCP 栈不知道（NAT 超时、服务器超时等）。
     * 仅关闭"空闲"连接不够，因为 TG 等应用可能持有"活跃但失效"的连接。
     */
    @Suppress("CognitiveComplexMethod")
    private fun executeRecovery(mode: RecoveryMode): Boolean {
        val startTime = SystemClock.elapsedRealtime()

        // 2025-fix-v13: 不再依赖 foregroundRecoveryHandler (异步)
        // 改为直接同步调用 BoxWrapperManager
        // 这确保恢复完成后才返回，避免 UI 显示不一致

        return try {
            when (mode) {
                RecoveryMode.QUICK -> {
                    // QUICK: 关闭空闲连接 + 重置所有连接
                    val closedIdle = BoxWrapperManager.closeIdleConnections(30)
                    // 2025-fix-v13: 即使是 QUICK 模式也强制重置所有连接
                    // 解决 TG 等应用持有"活跃但失效"连接的问题
                    val resetOk = BoxWrapperManager.resetAllConnections(true)
                    val cost = SystemClock.elapsedRealtime() - startTime
                    log("[Foreground] QUICK: closedIdle=$closedIdle, resetAll=$resetOk, cost=${cost}ms")
                    true
                }
                RecoveryMode.FULL -> {
                    // FULL: 完整恢复 + 重置所有连接
                    val recoverOk = BoxWrapperManager.recoverNetworkFull()
                    val resetOk = BoxWrapperManager.resetAllConnections(true)
                    val cost = SystemClock.elapsedRealtime() - startTime
                    log("[Foreground] FULL: recoverFull=$recoverOk, resetAll=$resetOk, cost=${cost}ms")
                    recoverOk
                }
                RecoveryMode.DEEP -> {
                    // DEEP: 深度恢复 (包含 DNS 清理) + 重置所有连接
                    val recoverOk = BoxWrapperManager.recoverNetworkDeep()
                    val resetOk = BoxWrapperManager.resetAllConnections(true)
                    val cost = SystemClock.elapsedRealtime() - startTime
                    log("[Foreground] DEEP: recoverDeep=$recoverOk, resetAll=$resetOk, cost=${cost}ms")
                    recoverOk
                }
            }
        } catch (e: Exception) {
            val cost = SystemClock.elapsedRealtime() - startTime
            Log.e(TAG, "[Foreground] recovery failed after ${cost}ms", e)
            false
        }
    }

    /**
     * 2025-fix-v11: 恢复模式枚举
     */
    private enum class RecoveryMode {
        QUICK,
        FULL,
        DEEP
    }

    fun getStateOrdinal(): Int = stateOrdinal

    fun getActiveLabel(): String = activeLabel

    fun getLastError(): String = lastError

    fun isManuallyStopped(): Boolean = manuallyStopped

    /**
     * 2025-fix-v6: 获取上次状态更新时间戳
     */
    fun getLastStateUpdateTime(): Long = lastStateUpdateAtMs.get()

    fun update(
        state: ServiceState? = null,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        var shouldStartBroadcast = false
        val updateStart = SystemClock.elapsedRealtime()
        synchronized(broadcastLock) {
            state?.let {
                val oldState = ServiceState.values().getOrNull(stateOrdinal)?.name ?: "UNKNOWN"
                stateOrdinal = it.ordinal
                log("state update: $oldState -> ${it.name}")
                // 2025-fix-v6: 同步状态到 VpnStateStore (跨进程持久化)
                // 这确保主进程恢复时可以直接读取真实状态，不依赖回调
                VpnStateStore.setActive(it == ServiceState.RUNNING)
            }
            activeLabel?.let {
                this.activeLabel = it
                // 2025-fix-v6: 同步 activeLabel 到 VpnStateStore
                VpnStateStore.setActiveLabel(it)
            }
            lastError?.let {
                this.lastError = it
                VpnStateStore.setLastError(it)
            }
            manuallyStopped?.let {
                this.manuallyStopped = it
                VpnStateStore.setManuallyStopped(it)
            }

            // 更新时间戳
            lastStateUpdateAtMs.set(SystemClock.elapsedRealtime())

            if (broadcasting) {
                broadcastPending = true
            } else {
                broadcasting = true
                shouldStartBroadcast = true
            }
        }

        if (shouldStartBroadcast) {
            drainBroadcastLoop()
        }
        Log.d(TAG, "[IPC] update completed in ${SystemClock.elapsedRealtime() - updateStart}ms")
    }

    fun registerCallback(callback: ISingBoxServiceCallback) {
        callbacks.register(callback)
        runCatching {
            callback.onStateChanged(stateOrdinal, activeLabel, lastError, manuallyStopped)
        }
    }

    fun unregisterCallback(callback: ISingBoxServiceCallback) {
        callbacks.unregister(callback)
    }

    private fun drainBroadcastLoop() {
        while (true) {
            val snapshot = synchronized(broadcastLock) {
                broadcastPending = false
                StateSnapshot(stateOrdinal, activeLabel, lastError, manuallyStopped)
            }

            val n = callbacks.beginBroadcast()
            Log.d(
                TAG,
                "[IPC] broadcasting to $n callbacks, state=${ServiceState.values().getOrNull(snapshot.stateOrdinal)?.name}"
            )
            try {
                for (i in 0 until n) {
                    runCatching {
                        callbacks.getBroadcastItem(i)
                            .onStateChanged(snapshot.stateOrdinal, snapshot.activeLabel, snapshot.lastError, snapshot.manuallyStopped)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }

            val shouldContinue = synchronized(broadcastLock) {
                if (broadcastPending) {
                    true
                } else {
                    broadcasting = false
                    false
                }
            }

            if (!shouldContinue) return
        }
    }

    private data class StateSnapshot(
        val stateOrdinal: Int,
        val activeLabel: String,
        val lastError: String,
        val manuallyStopped: Boolean
    )

    /**
     * 热重载结果码
     */
    object HotReloadResult {
        const val SUCCESS = 0
        const val VPN_NOT_RUNNING = 1
        const val KERNEL_ERROR = 2
        const val UNKNOWN_ERROR = 3
    }

    /**
     * 内核级热重载配置
     * 通过 ServiceStateHolder.instance 访问 SingBoxService
     * 直接调用 Go 层 StartOrReloadService，不销毁 VPN 服务
     *
     * @param configContent 新的配置内容 (JSON)
     * @return 热重载结果码 (HotReloadResult)
     */
    fun hotReloadConfig(configContent: String): Int {
        log("[HotReload] IPC request received")

        // 检查 VPN 是否运行
        if (stateOrdinal != ServiceState.RUNNING.ordinal) {
            Log.w(TAG, "[HotReload] VPN not running, state=$stateOrdinal")
            return HotReloadResult.VPN_NOT_RUNNING
        }

        // 获取 SingBoxService 实例
        val service = ServiceStateHolder.instance
        if (service == null) {
            Log.e(TAG, "[HotReload] SingBoxService instance is null")
            return HotReloadResult.VPN_NOT_RUNNING
        }

        // 调用 Service 的热重载方法
        return try {
            val result = service.performHotReloadSync(configContent)
            if (result) {
                log("[HotReload] Success")
                HotReloadResult.SUCCESS
            } else {
                Log.e(TAG, "[HotReload] Kernel returned false")
                HotReloadResult.KERNEL_ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HotReload] Exception: ${e.message}", e)
            HotReloadResult.UNKNOWN_ERROR
        }
    }
}
