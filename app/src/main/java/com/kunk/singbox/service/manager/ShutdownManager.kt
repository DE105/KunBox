package com.kunk.singbox.service.manager

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.core.SelectorManager as CoreSelectorManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnKeepaliveWorker
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.notification.VpnNotificationManager
import com.kunk.singbox.service.network.NetworkManager
import com.kunk.singbox.service.network.TrafficMonitor
import com.kunk.singbox.utils.NetworkClient
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.*

/**
 * VPN 关闭管理器
 * 负责完整的 VPN 关闭流程，包括：
 * - 状态重置
 * - 资源清理
 * - 异步关闭
 * - 跨配置切换支持
 */
class ShutdownManager(
    private val context: Context,
    private val cleanupScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ShutdownManager"
        private const val FAST_PORT_RELEASE_WAIT_MS = 1500L
    }

    /**
     * 关闭回调接口
     */
    interface Callbacks {
        // 状态管理
        fun updateServiceState(state: ServiceState)
        fun updateTileState()
        fun stopForegroundService()
        fun stopSelf()

        // 组件管理
        fun cancelStartVpnJob(): Job?
        fun cancelVpnHealthJob()
        fun cancelRemoteStateUpdateJob()
        fun cancelRouteGroupAutoSelectJob()

        // 资源清理
        fun stopForeignVpnMonitor()
        fun tryClearRunningServiceForLibbox()
        fun unregisterScreenStateReceiver()
        fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?)

        // 获取状态
        fun isServiceRunning(): Boolean
        fun getVpnInterface(): ParcelFileDescriptor?
        fun getCurrentInterfaceListener(): InterfaceUpdateListener?
        fun getConnectivityManager(): ConnectivityManager?

        // 设置状态
        fun setVpnInterface(fd: ParcelFileDescriptor?)
        fun setIsRunning(running: Boolean)
        fun setRealTimeNodeName(name: String?)
        fun setVpnLinkValidated(validated: Boolean)
        fun setNoPhysicalNetworkWarningLogged(logged: Boolean)
        fun setDefaultInterfaceName(name: String)
        fun setNetworkCallbackReady(ready: Boolean)
        fun setLastKnownNetwork(network: android.net.Network?)
        fun clearUnderlyingNetworks()

        // 获取配置路径用于重启
        fun getPendingStartConfigPath(): String?
        fun clearPendingStartConfigPath()
        fun startVpn(configPath: String)

        // 检查 VPN 接口是否可复用
        fun hasExistingTunInterface(): Boolean
    }

    /**
     * 关闭选项
     */
    data class ShutdownOptions(
        val stopService: Boolean,
        val preserveTunInterface: Boolean = !stopService,
        val proxyPort: Int = 0  // 需要等待释放的代理端口
    )

    /**
     * 执行完整的 VPN 关闭流程
     */
    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
    fun stopVpn(
        options: ShutdownOptions,
        coreManager: CoreManager,
        commandManager: CommandManager,
        trafficMonitor: TrafficMonitor,
        networkManager: NetworkManager?,
        notificationManager: VpnNotificationManager,
        selectorManager: SelectorManager,
        platformInterfaceImpl: PlatformInterfaceImpl,
        callbacks: Callbacks
    ): Job {
        val stopService = options.stopService
        val proxyPort = options.proxyPort

        // 1. 取消进行中的任务
        val jobToJoin = callbacks.cancelStartVpnJob()
        callbacks.cancelVpnHealthJob()
        callbacks.cancelRemoteStateUpdateJob()
        callbacks.cancelRouteGroupAutoSelectJob()

        // 2. 取消 WorkManager 保活任务
        VpnKeepaliveWorker.cancel(context)
        Log.i(TAG, "VPN keepalive worker cancelled")

        // 4. 重置通知管理器状态
        notificationManager.resetState()

        // 5. 停止流量监控
        trafficMonitor.stop()

        // 6. 重置网络管理器
        networkManager?.reset()

        // 7. 停止外部 VPN 监控
        callbacks.stopForeignVpnMonitor()

        // 8. 重置关键网络状态
        callbacks.setVpnLinkValidated(false)
        callbacks.setNoPhysicalNetworkWarningLogged(false)
        callbacks.setDefaultInterfaceName("")

        if (stopService) {
            callbacks.setNetworkCallbackReady(false)
            callbacks.setLastKnownNetwork(null)
            callbacks.clearUnderlyingNetworks()
        } else {
            callbacks.setNetworkCallbackReady(false)
        }

        // 9. 清除 libbox 运行服务
        callbacks.tryClearRunningServiceForLibbox()

        // 10. 释放 BoxWrapperManager (移到 CommandManager.stop 内部处理)
        // BoxWrapperManager.release() -- 已在 CommandManager.stop() 中调用

        // 11. 清除 SelectorManager 状态
        CoreSelectorManager.clear()
        selectorManager.clear()

        Log.i(TAG, "stopVpn(stopService=$stopService, proxyPort=$proxyPort)")

        // 12. 重置节点名称和运行状态
        callbacks.setRealTimeNodeName(null)
        callbacks.setIsRunning(false)
        NetworkClient.onVpnStateChanged(false)

        // 13. 获取需要关闭的资源
        val listener = callbacks.getCurrentInterfaceListener()

        val interfaceToClose: ParcelFileDescriptor?
        if (stopService) {
            interfaceToClose = callbacks.getVpnInterface()
            callbacks.setVpnInterface(null)
            coreManager.setVpnInterface(null)
        } else {
            interfaceToClose = null
            Log.i(TAG, "Keeping vpnInterface for reuse")
        }

        // 14. 释放锁
        if (stopService) {
            coreManager.releaseLocks()
            callbacks.unregisterScreenStateReceiver()
        }

        // 15. 异步清理（包括停止命令管理器和等待端口释放）
        return cleanupScope.launch(NonCancellable) {
            try {
                jobToJoin?.join()
            } catch (_: Exception) {}

            if (stopService) {
                withContext(Dispatchers.Main) {
                    callbacks.stopForegroundService()
                    runCatching {
                        val manager = context.getSystemService(NotificationManager::class.java)
                        manager.cancel(VpnNotificationManager.NOTIFICATION_ID)
                    }
                    VpnTileService.persistVpnState(context, false)
                    VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
                    VpnTileService.persistVpnPending(context, "")
                    callbacks.updateServiceState(ServiceState.STOPPED)
                    callbacks.updateTileState()
                }
            }

            // 关键修复：在异步任务中停止命令管理器并等待端口释放
            // 快速停机模式下，缩短主路径等待时间并避免阻塞 UI
            commandManager.stopAndWaitPortRelease(
                proxyPort = proxyPort,
                waitTimeoutMs = FAST_PORT_RELEASE_WAIT_MS,
                forceKillOnTimeout = false
            ).onFailure { e ->
                Log.w(TAG, "Error closing command server/client", e)
            }

            // 跨配置切换时不关闭 interface monitor
            if (stopService) {
                try {
                    platformInterfaceImpl.closeDefaultInterfaceMonitor(listener)
                } catch (_: Exception) {}
            }

            try {
                withTimeout(2000L) {
                    if (interfaceToClose != null) {
                        try { interfaceToClose.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Graceful close failed or timed out", e)
            }

            // 使用 stopService 参数决定是否完全停止，而非依赖 vpnInterface 是否为 null
            // 这确保用户明确请求停止时，通知总会被取消
            withContext(Dispatchers.Main) {
                if (stopService) {
                    callbacks.stopSelf()
                    Log.i(TAG, "VPN stopped")
                } else {
                    Log.i(TAG, "Config reload: boxService closed, keeping TUN and foreground")
                }
            }

            // 处理排队的启动请求
            val startAfterStop = callbacks.getPendingStartConfigPath()
            callbacks.clearPendingStartConfigPath()

            if (!startAfterStop.isNullOrBlank()) {
                val hasExistingTun = callbacks.hasExistingTunInterface()
                if (!hasExistingTun) {
                    waitForSystemVpnDown(callbacks.getConnectivityManager(), 1500L)
                } else {
                    Log.i(TAG, "Skipping waitForSystemVpnDown: TUN interface preserved")
                }
                withContext(Dispatchers.Main) {
                    callbacks.startVpn(startAfterStop)
                }
            }
        }
    }

    private suspend fun waitForSystemVpnDown(cm: ConnectivityManager?, timeoutMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || cm == null) return

        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val hasVpn = runCatching {
                @Suppress("DEPRECATION")
                cm.allNetworks.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }.getOrDefault(false)

            if (!hasVpn) return
            delay(50)
        }
    }
}
