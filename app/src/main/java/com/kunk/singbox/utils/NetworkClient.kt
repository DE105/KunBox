package com.kunk.singbox.utils

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttpClient 单例
 * 优化网络连接复用，减少握手开销
 */
object NetworkClient {

    // 适当放宽超时，适应 VPN 环境下的 DNS 解析延迟
    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 20L
    private const val WRITE_TIMEOUT = 20L
    
    // 连接池配置：保持 5 个空闲连接，存活 5 分钟
    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    /**
     * 轻量级重试拦截器
     *
     * 优化说明:
     * - 移除了 Thread.sleep() 阻塞等待，避免阻塞 OkHttp 线程池
     * - 仅对可重试的网络错误进行一次快速重试
     * - 复杂的重试逻辑应在业务层使用协程处理
     */
    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()

        try {
            chain.proceed(request)
        } catch (e: IOException) {
            // 仅对连接相关的瞬时错误进行一次快速重试，不等待
            val isRetryable = e.message?.let { msg ->
                msg.contains("Connection reset", ignoreCase = true) ||
                msg.contains("Connection refused", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true)
            } ?: false

            if (isRetryable) {
                try {
                    chain.proceed(request)
                } catch (retryException: IOException) {
                    throw retryException
                }
            } else {
                throw e
            }
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .addInterceptor(retryInterceptor) // 添加重试拦截器
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 获取一个新的 Builder，共享连接池但可以自定义超时等配置
     */
    fun newBuilder(): OkHttpClient.Builder {
        return client.newBuilder()
    }

    /**
     * 创建一个具有自定义超时的 OkHttpClient
     * @param connectTimeoutSeconds 连接超时时间（秒）
     * @param readTimeoutSeconds 读取超时时间（秒）
     * @param writeTimeoutSeconds 写入超时时间（秒）
     */
    fun createClientWithTimeout(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds
    ): OkHttpClient {
        return newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 创建一个具有自定义超时且不带重试的 OkHttpClient
     * 用于订阅获取等需要精确控制超时的场景
     * @param connectTimeoutSeconds 连接超时时间（秒）
     * @param readTimeoutSeconds 读取超时时间（秒）
     * @param writeTimeoutSeconds 写入超时时间（秒）
     */
    fun createClientWithoutRetry(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .retryOnConnectionFailure(false) // 不自动重试
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 清理连接池
     * 当 VPN 状态变化或网络切换时调用，避免复用失效的 Socket
     */
    fun clearConnectionPool() {
        connectionPool.evictAll()
    }

    /**
     * 创建一个使用本地 HTTP 代理的 OkHttpClient
     * 用于在 VPN 运行时让应用自身的请求走代理
     * @param proxyPort 代理端口（sing-box 的 mixed 端口）
     * @param connectTimeoutSeconds 连接超时时间（秒）
     * @param readTimeoutSeconds 读取超时时间（秒）
     * @param writeTimeoutSeconds 写入超时时间（秒）
     */
    fun createClientWithProxy(
        proxyPort: Int,
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds
    ): OkHttpClient {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress("127.0.0.1", proxyPort)
        )
        return OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}