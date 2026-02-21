package com.kunbox.singbox.utils

import android.content.Context
import com.kunbox.singbox.ipc.VpnStateStore
import com.kunbox.singbox.model.AppSettings
import com.kunbox.singbox.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * Proxy-aware OkHttp client provider.
 *
 * Important: In VPN (TUN) mode we intentionally exclude this app package from VPN routing
 * (see VpnTunManager.configurePerAppVpn -> addDisallowedApplication(selfPackage)).
 *
 * Therefore:
 * - Direct OkHttp requests will bypass the tunnel.
 * - When the core is active, app-side requests should use the local HTTP proxy (127.0.0.1:proxyPort)
 *   to ensure they go through sing-box.
 */
object ProxyAwareOkHttpClient {
    private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 15L
    private const val DEFAULT_READ_TIMEOUT_SECONDS = 20L
    private const val DEFAULT_WRITE_TIMEOUT_SECONDS = 20L

    /**
     * Build a proxy-aware client using the provided [settings].
     *
     * Uses local proxy only when the core is ACTIVE (VpnStateStore.getActive() == true).
     */
    fun get(
        settings: AppSettings,
        connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
        readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_SECONDS,
        writeTimeoutSeconds: Long = DEFAULT_WRITE_TIMEOUT_SECONDS,
        directWithRetry: Boolean = true
    ): OkHttpClient {
        val proxyPort = settings.proxyPort
        val coreActive = VpnStateStore.getActive()
        val shouldUseProxy = coreActive && proxyPort > 0

        return if (shouldUseProxy) {
            NetworkClient.createClientWithProxy(
                proxyPort = proxyPort,
                connectTimeoutSeconds = connectTimeoutSeconds,
                readTimeoutSeconds = readTimeoutSeconds,
                writeTimeoutSeconds = writeTimeoutSeconds
            )
        } else {
            if (directWithRetry) {
                NetworkClient.createClientWithTimeout(
                    connectTimeoutSeconds = connectTimeoutSeconds,
                    readTimeoutSeconds = readTimeoutSeconds,
                    writeTimeoutSeconds = writeTimeoutSeconds
                )
            } else {
                NetworkClient.createClientWithoutRetry(
                    connectTimeoutSeconds = connectTimeoutSeconds,
                    readTimeoutSeconds = readTimeoutSeconds,
                    writeTimeoutSeconds = writeTimeoutSeconds
                )
            }
        }
    }

    /**
     * Suspended variant that reads [AppSettings] from [SettingsRepository].
     */
    suspend fun get(
        context: Context,
        connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
        readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_SECONDS,
        writeTimeoutSeconds: Long = DEFAULT_WRITE_TIMEOUT_SECONDS,
        directWithRetry: Boolean = true
    ): OkHttpClient {
        val settings = SettingsRepository.getInstance(context).settings.first()
        return get(
            settings = settings,
            connectTimeoutSeconds = connectTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds,
            writeTimeoutSeconds = writeTimeoutSeconds,
            directWithRetry = directWithRetry
        )
    }
}
