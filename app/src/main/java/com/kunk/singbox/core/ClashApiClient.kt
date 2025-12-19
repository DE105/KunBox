package com.kunk.singbox.core

import com.google.gson.annotations.SerializedName
import okhttp3.WebSocket

/**
 * Clash API 客户端
 * 用于通过 sing-box 的 Clash API 测试节点延迟
 */
class ClashApiClient(
    baseUrl: String = "http://127.0.0.1:9090"
) {
    // This project is migrating to native-only (libbox) control paths.
    // Clash API (HTTP/WebSocket) is intentionally disabled.
    private var baseUrl: String = baseUrl

    fun setBaseUrl(baseUrl: String) {
        this.baseUrl = baseUrl
    }

    fun getBaseUrl(): String = baseUrl
    
    /**
     * 获取所有代理节点
     */
    suspend fun getProxies(): ProxiesResponse? = null
    
    /**
     * 测试单个节点延迟
     * @param proxyName 节点名称
     * @param testUrl 测试 URL
     * @param timeout 超时时间（毫秒）
     * @param type 延迟测试类型: "tcp", "real", "handshake"
     * @return 延迟（毫秒），-1 表示失败
     */
    suspend fun testProxyDelay(
        proxyName: String,
        testUrl: String,
        timeout: Long,
        type: String
    ): Long = -1L
    
    /**
     * 批量测试节点延迟
     */
    suspend fun testProxiesDelay(
        proxyNames: List<String>,
        testUrl: String,
        timeout: Long,
        type: String,
        onResult: (name: String, delay: Long) -> Unit
    ) {
        proxyNames.forEach { onResult(it, -1L) }
    }
    
    /**
     * 检查 Clash API 是否可用
     */
    suspend fun isAvailable(): Boolean = false

    /**
     * 选择代理节点
     * PUT /proxies/{selectorName}
     * Body: {"name": "proxyName"}
     */
    suspend fun selectProxy(selectorName: String, proxyName: String): Boolean = false

    /**
     * 获取当前 selector 选中的代理
     */
    suspend fun getCurrentSelection(selectorName: String): String? = null
    /**
     * 连接到流量 WebSocket
     */
    fun connectTrafficWebSocket(onTraffic: (up: Long, down: Long) -> Unit): WebSocket? = null
}

// API 响应数据类
data class ProxiesResponse(
    @SerializedName("proxies")
    val proxies: Map<String, ProxyInfo>
)

data class ProxyInfo(
    @SerializedName("name")
    val name: String,
    @SerializedName("type")
    val type: String,
    @SerializedName("all")
    val all: List<String>? = null,
    @SerializedName("now")
    val now: String? = null,
    @SerializedName("history")
    val history: List<HistoryItem>? = null
)

data class HistoryItem(
    @SerializedName("time")
    val time: String,
    @SerializedName("delay")
    val delay: Int
)

data class DelayResponse(
    @SerializedName("delay")
    val delay: Int
)
