package com.kunk.singbox.ipc

import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.manager.BackgroundPowerManager
import com.kunk.singbox.service.manager.ServiceStateHolder
import java.util.concurrent.atomic.AtomicLong

object SingBoxIpcHub {
    private const val TAG = "SingBoxIpcHub"

    // 高频状态更新时避免 CPU 空转，50ms 是 RemoteCallbackList 回调的合理间隔
    private const val MIN_BROADCAST_INTERVAL_MS = 50L

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

    private fun getStateName(ordinal: Int): String =
        ServiceState.values().getOrNull(ordinal)?.name ?: "UNKNOWN"

    private val broadcastLock = Any()
    @Volatile private var broadcasting: Boolean = false
    @Volatile private var broadcastPending: Boolean = false
    private val lastBroadcastAtMs = AtomicLong(0L)

    // 省电管理器引用，由 SingBoxService 设置
    @Volatile
    private var powerManager: BackgroundPowerManager? = null

    // 状态更新时间戳，用于检测回调通道是否正常
    private val lastStateUpdateAtMs = AtomicLong(0L)

    // 上次应用返回前台的时间戳，用于防抖
    private val lastForegroundAtMs = AtomicLong(0L)

    // 上次应用进入后台的时间戳，用于计算后台时长
    private val lastBackgroundAtMs = AtomicLong(0L)

    // 前台恢复阈值
    private const val FOREGROUND_RESET_DEBOUNCE_MS = 2_000L
    private const val FOREGROUND_RECOVERY_MIN_BACKGROUND_MS = 5_000L

    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

    /**
     * 接收主进程的 App 生命周期通知
     */
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

    @Suppress("ReturnCount")
    private fun performForegroundRecovery() {
        val isVpnRunning = stateOrdinal == ServiceState.RUNNING.ordinal
        if (!isVpnRunning) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundAtMs.get() < FOREGROUND_RESET_DEBOUNCE_MS) return

        val backgroundDuration = now - lastBackgroundAtMs.get()
        if (lastBackgroundAtMs.get() == 0L || backgroundDuration < FOREGROUND_RECOVERY_MIN_BACKGROUND_MS) {
            return
        }

        lastForegroundAtMs.set(now)
        log("[Foreground] Long background (${backgroundDuration / 1000}s), waking and resetting network")

        BoxWrapperManager.wakeAndResetNetwork("ipc_foreground")
    }

    fun getStateOrdinal(): Int = stateOrdinal

    fun getActiveLabel(): String = activeLabel

    fun getLastError(): String = lastError

    fun isManuallyStopped(): Boolean = manuallyStopped

    /**
     * 获取上次状态更新时间戳
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
                VpnStateStore.setActive(it == ServiceState.RUNNING)
            }
            activeLabel?.let {
                this.activeLabel = it
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
            // 限制广播频率，防止高频状态更新导致 CPU 空转
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastBroadcastAtMs.get()
            if (elapsed < MIN_BROADCAST_INTERVAL_MS) {
                try {
                    Thread.sleep(MIN_BROADCAST_INTERVAL_MS - elapsed)
                } catch (_: InterruptedException) {
                    return
                }
            }
            lastBroadcastAtMs.set(SystemClock.elapsedRealtime())

            val snapshot = synchronized(broadcastLock) {
                broadcastPending = false
                StateSnapshot(stateOrdinal, activeLabel, lastError, manuallyStopped)
            }

            val n = callbacks.beginBroadcast()
            Log.d(
                TAG,
                "[IPC] broadcasting to $n callbacks, state=${getStateName(snapshot.stateOrdinal)}"
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
