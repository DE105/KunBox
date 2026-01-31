package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.utils.perf.StateCache
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * 连接管理器
 * 负责网络状态监控、底层网络绑定、连接重置等
 */
class ConnectManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectManager"
        private const val CONNECTION_RESET_DEBOUNCE_MS = 2000L
        private const val STARTUP_WINDOW_MS = 3000L
    }

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastKnownNetwork: Network? = null
    private var setUnderlyingNetworksFn: ((Array<Network>?) -> Unit)? = null

    @Volatile
    private var isReady = false

    private val vpnStartedAtMs = AtomicLong(0)
    private val lastConnectionResetAtMs = AtomicLong(0)

    private var onNetworkChanged: ((Network?) -> Unit)? = null
    private var onNetworkLost: (() -> Unit)? = null

    data class NetworkState(
        val network: Network?,
        val isValid: Boolean,
        val hasInternet: Boolean,
        val isNotVpn: Boolean
    )

    fun init(
        onNetworkChanged: (Network?) -> Unit,
        onNetworkLost: () -> Unit,
        setUnderlyingNetworksFn: ((Array<Network>?) -> Unit)? = null
    ): Result<Unit> {
        return runCatching {
            this.onNetworkChanged = onNetworkChanged
            this.onNetworkLost = onNetworkLost
            this.setUnderlyingNetworksFn = setUnderlyingNetworksFn
            Log.i(TAG, "ConnectManager initialized")
        }
    }

    fun registerNetworkCallback(): Result<Unit> {
        return runCatching {
            val cm = connectivityManager
                ?: throw IllegalStateException("ConnectivityManager not available")

            if (networkCallback != null) {
                Log.w(TAG, "Network callback already registered")
                return@runCatching
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleNetworkAvailable(network)
                }

                override fun onLost(network: Network) {
                    handleNetworkLost(network)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities
                ) {
                    handleCapabilitiesChanged(network)
                }
            }

            cm.registerNetworkCallback(request, networkCallback!!)
            Log.i(TAG, "Network callback registered")
        }
    }

    /**
     * 注销网络回调
     */
    fun unregisterNetworkCallback(): Result<Unit> {
        return runCatching {
            networkCallback?.let { callback ->
                runCatching {
                    connectivityManager?.unregisterNetworkCallback(callback)
                }
            }
            networkCallback = null
            Log.i(TAG, "Network callback unregistered")
        }
    }

    /**
     * 获取当前物理网络 (使用缓存)
     */
    fun getCurrentNetwork(): Network? {
        return StateCache.getNetwork {
            getPhysicalNetwork()
        }
    }

    /**
     * 获取物理网络 (不使用缓存)
     */
    fun getPhysicalNetwork(): Network? {
        val cm = connectivityManager ?: return null

        // 优先返回已缓存的网络
        lastKnownNetwork?.let { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (isValidPhysicalNetwork(caps)) {
                return network
            }
        }

        // 查找默认网络
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork
            val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (isValidPhysicalNetwork(caps)) {
                lastKnownNetwork = activeNetwork
                return activeNetwork
            }
        }

        return null
    }

    /**
     * 等待可用的物理网络
     */
    suspend fun waitForNetwork(timeoutMs: Long): Result<Network?> {
        return runCatching {
            withTimeout(timeoutMs) {
                while (!isReady || lastKnownNetwork == null) {
                    delay(100)
                }
                lastKnownNetwork
            }
        }
    }

    /**
     * 标记 VPN 启动
     */
    fun markVpnStarted() {
        vpnStartedAtMs.set(SystemClock.elapsedRealtime())
    }

    /**
     * 是否在启动窗口期内
     */
    fun isInStartupWindow(): Boolean {
        val startedAt = vpnStartedAtMs.get()
        if (startedAt == 0L) return false
        return (SystemClock.elapsedRealtime() - startedAt) < STARTUP_WINDOW_MS
    }

    /**
     * 设置底层网络 (无防抖，参考 v2rayNG)
     * 2025-fix-v16: v2rayNG 在每次网络回调时都立即调用，不做防抖
     */
    fun setUnderlyingNetworks(
        networks: Array<Network>?,
        setUnderlyingFn: (Array<Network>?) -> Unit
    ): Result<Boolean> {
        return runCatching {
            // 检查启动窗口期
            if (isInStartupWindow()) {
                Log.d(TAG, "Skipping setUnderlyingNetworks during startup window")
                return@runCatching false
            }

            // 2025-fix-v16: 移除防抖，参考 v2rayNG 立即执行
            setUnderlyingFn(networks)
            Log.i(TAG, "setUnderlyingNetworks: ${networks?.size ?: 0} networks")
            true
        }
    }

    /**
     * 重置连接 (带防抖)
     */
    fun resetConnections(resetFn: () -> Unit): Result<Boolean> {
        return runCatching {
            val now = SystemClock.elapsedRealtime()
            val last = lastConnectionResetAtMs.get()
            if ((now - last) < CONNECTION_RESET_DEBOUNCE_MS) {
                Log.d(TAG, "Debouncing connection reset")
                return@runCatching false
            }

            lastConnectionResetAtMs.set(now)
            resetFn()
            Log.i(TAG, "Connections reset")
            true
        }
    }

    /**
     * 检查网络状态
     */
    fun getNetworkState(): NetworkState {
        val network = lastKnownNetwork
        val caps = network?.let { connectivityManager?.getNetworkCapabilities(it) }

        return NetworkState(
            network = network,
            isValid = network != null,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            isNotVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        )
    }

    /**
     * 是否就绪
     */
    fun isReady(): Boolean = isReady

    /**
     * 清理资源
     */
    fun cleanup(): Result<Unit> {
        return runCatching {
            unregisterNetworkCallback()
            lastKnownNetwork = null
            isReady = false
            onNetworkChanged = null
            onNetworkLost = null
            setUnderlyingNetworksFn = null
            StateCache.invalidateNetworkCache()
            Log.i(TAG, "ConnectManager cleaned up")
        }
    }

    private fun handleNetworkAvailable(network: Network) {
        Log.i(TAG, "Network available: $network")
        val previousNetwork = lastKnownNetwork
        lastKnownNetwork = network
        StateCache.updateNetworkCache(network)
        isReady = true

        setUnderlyingNetworksFn?.invoke(arrayOf(network))
        onNetworkChanged?.invoke(network)

        // 网络切换时重置所有连接
        if (previousNetwork != null && previousNetwork != network) {
            serviceScope.launch(Dispatchers.IO) {
                Log.i(TAG, "[NetworkSwitch] Resetting all connections")
                BoxWrapperManager.resetAllConnections(true)
            }
        }
    }

    private fun handleNetworkLost(network: Network) {
        Log.i(TAG, "Network lost: $network")
        if (lastKnownNetwork == network) {
            lastKnownNetwork = null
            StateCache.invalidateNetworkCache()
            setUnderlyingNetworksFn?.invoke(null)
        }
        onNetworkLost?.invoke()
    }

    private fun handleCapabilitiesChanged(network: Network) {
        setUnderlyingNetworksFn?.invoke(arrayOf(network))

        if (lastKnownNetwork != network) {
            val previousNetwork = lastKnownNetwork
            lastKnownNetwork = network
            StateCache.updateNetworkCache(network)
            onNetworkChanged?.invoke(network)

            if (previousNetwork != null) {
                serviceScope.launch(Dispatchers.IO) {
                    Log.i(TAG, "[CapChange] Resetting all connections")
                    BoxWrapperManager.resetAllConnections(true)
                }
            }
        }
    }

    private fun isValidPhysicalNetwork(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}
