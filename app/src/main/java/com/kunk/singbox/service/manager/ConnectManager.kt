package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.utils.perf.StateCache
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * 连接管理器
 * 负责网络状态监控、底层网络绑定、连接重置等
 * 使用 Result<T> 返回值模式
 */
class ConnectManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectManager"
        // 2025-fix-v16: 参考 v2rayNG，移除 setUnderlyingNetworks 的防抖
        // v2rayNG 在每次 onAvailable/onCapabilitiesChanged 时都立即调用 setUnderlyingNetworks
        // 这让 Android 系统能及时通知上层应用网络状态变化，应用会主动重建连接
        private const val CONNECTION_RESET_DEBOUNCE_MS = 2000L // 仅用于连接重置
        private const val STARTUP_WINDOW_MS = 3000L
    }

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastKnownNetwork: Network? = null

    // 2025-fix-v16: 参考 v2rayNG，添加直接设置底层网络的回调
    // 这样可以在 NetworkCallback 内部立即调用 setUnderlyingNetworks
    private var setUnderlyingNetworksFn: ((Array<Network>?) -> Unit)? = null

    @Volatile
    private var isReady = false

    @Volatile
    private var networkChangeResetEnabled = true

    private val vpnStartedAtMs = AtomicLong(0)
    private val lastConnectionResetAtMs = AtomicLong(0)

    private var onNetworkChanged: ((Network?) -> Unit)? = null
    private var onNetworkLost: (() -> Unit)? = null
    private var onNetworkChangeReset: ((String) -> Unit)? = null

    /**
     * 网络状态
     */
    data class NetworkState(
        val network: Network?,
        val isValid: Boolean,
        val hasInternet: Boolean,
        val isNotVpn: Boolean
    )

    /**
     * 初始化管理器
     * @param setUnderlyingNetworksFn 直接设置底层网络的回调，用于在 NetworkCallback 内立即调用
     */
    fun init(
        onNetworkChanged: (Network?) -> Unit,
        onNetworkLost: () -> Unit,
        onNetworkChangeReset: ((String) -> Unit)? = null,
        setUnderlyingNetworksFn: ((Array<Network>?) -> Unit)? = null
    ): Result<Unit> {
        return runCatching {
            this.onNetworkChanged = onNetworkChanged
            this.onNetworkLost = onNetworkLost
            this.onNetworkChangeReset = onNetworkChangeReset
            this.setUnderlyingNetworksFn = setUnderlyingNetworksFn
            Log.i(TAG, "ConnectManager initialized (v2rayNG-style immediate underlying network update)")
        }
    }

    /**
     * 设置网络变化时是否重置连接
     */
    fun setNetworkChangeResetEnabled(enabled: Boolean) {
        networkChangeResetEnabled = enabled
        Log.i(TAG, "Network change reset enabled: $enabled")
    }

    /**
     * 注册网络回调
     */
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
            onNetworkChangeReset = null
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

        // 2025-fix-v16: 参考 v2rayNG，立即调用 setUnderlyingNetworks
        // 这让 Android 系统能及时通知上层应用网络状态变化
        setUnderlyingNetworksFn?.invoke(arrayOf(network))

        onNetworkChanged?.invoke(network)

        if (previousNetwork != null && previousNetwork != network) {
            triggerNetworkChangeReset("network_switch")
        }
    }

    private fun handleNetworkLost(network: Network) {
        Log.i(TAG, "Network lost: $network")
        if (lastKnownNetwork == network) {
            lastKnownNetwork = null
            StateCache.invalidateNetworkCache()

            // 2025-fix-v16: 参考 v2rayNG，网络丢失时设置为 null
            setUnderlyingNetworksFn?.invoke(null)
        }
        onNetworkLost?.invoke()
    }

    private fun handleCapabilitiesChanged(network: Network) {
        // 2025-fix-v16: 参考 v2rayNG，每次能力变化都更新底层网络
        // 原注释: "it's a good idea to refresh capabilities"
        setUnderlyingNetworksFn?.invoke(arrayOf(network))

        if (lastKnownNetwork != network) {
            val previousNetwork = lastKnownNetwork
            lastKnownNetwork = network
            StateCache.updateNetworkCache(network)
            onNetworkChanged?.invoke(network)

            if (previousNetwork != null) {
                triggerNetworkChangeReset("capabilities_change")
            }
        }
    }

    private fun isValidPhysicalNetwork(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun triggerNetworkChangeReset(reason: String) {
        if (!networkChangeResetEnabled) {
            Log.d(TAG, "Network change reset disabled, skipping")
            return
        }

        // 2025-fix-v16: 由于 setUnderlyingNetworks 已经立即触发，
        // 这里的 NetworkBump 变成了可选的补充措施
        // 保持原有防抖逻辑，避免频繁触发
        val now = SystemClock.elapsedRealtime()
        val last = lastConnectionResetAtMs.get()
        if ((now - last) < CONNECTION_RESET_DEBOUNCE_MS) {
            Log.d(TAG, "Debouncing network change reset")
            return
        }

        serviceScope.launch {
            delay(100) // 短暂延迟，让 setUnderlyingNetworks 生效
            Log.i(TAG, "Triggering network change reset: $reason")
            onNetworkChangeReset?.invoke(reason)
        }
    }
}
