package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.util.Log
import io.nekohasekai.libbox.BoxService
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 核心网络重置管理器
 * 负责管理 BoxService 的网络栈重置逻辑，包括：
 * - 防抖控制
 * - 失败计数和自动重启
 * - 连接关闭和网络重置
 */
class CoreNetworkResetManager(
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CoreNetworkResetManager"
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val FAILURE_TIMEOUT_MS = 30000L
    }

    interface Callbacks {
        fun getBoxService(): BoxService?
        suspend fun restartVpnService(reason: String)
    }

    private var callbacks: Callbacks? = null

    private val lastResetAtMs = AtomicLong(0L)
    private val lastSuccessfulResetAtMs = AtomicLong(0L)
    private val failureCounter = AtomicInteger(0)
    private var resetJob: Job? = null

    var debounceMs: Long = 500L

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * 请求核心网络重置
     */
    fun requestReset(reason: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val last = lastResetAtMs.get()

        // 检查是否需要完全重启
        val lastSuccess = lastSuccessfulResetAtMs.get()
        val timeSinceLastSuccess = now - lastSuccess
        val failures = failureCounter.get()

        if (failures >= MAX_CONSECUTIVE_FAILURES && timeSinceLastSuccess > FAILURE_TIMEOUT_MS) {
            Log.w(TAG, "Too many reset failures ($failures), restarting service")
            serviceScope.launch {
                callbacks?.restartVpnService("Excessive network reset failures")
            }
            return
        }

        val minInterval = if (force) 100L else debounceMs

        if (force) {
            if (now - last < minInterval) return
            lastResetAtMs.set(now)
            resetJob?.cancel()
            resetJob = null
            serviceScope.launch {
                performForceReset(reason)
            }
            return
        }

        val delayMs = (debounceMs - (now - last)).coerceAtLeast(0L)
        if (delayMs <= 0L) {
            lastResetAtMs.set(now)
            resetJob?.cancel()
            resetJob = null
            serviceScope.launch {
                performReset(reason)
            }
            return
        }

        if (resetJob?.isActive == true) return
        resetJob = serviceScope.launch {
            delay(delayMs)
            val t = SystemClock.elapsedRealtime()
            val last2 = lastResetAtMs.get()
            if (t - last2 < debounceMs) return@launch
            lastResetAtMs.set(t)
            performReset(reason)
        }
    }

    private suspend fun performForceReset(reason: String) {
        try {
            val service = callbacks?.getBoxService()

            // Step 1: 尝试关闭连接
            try {
                service?.let { svc ->
                    val closeMethod = svc.javaClass.methods.find {
                        it.name == "closeConnections" && it.parameterCount == 0
                    }
                    closeMethod?.invoke(svc)
                }
            } catch (_: Exception) {}

            // Step 2: 延迟等待
            delay(150)

            // Step 3: 重置网络栈
            service?.resetNetwork()

            failureCounter.set(0)
            lastSuccessfulResetAtMs.set(SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            val newFailures = failureCounter.incrementAndGet()
            Log.w(TAG, "Force reset failed (reason=$reason, failures=$newFailures)", e)
        }
    }

    private suspend fun performReset(reason: String) {
        try {
            callbacks?.getBoxService()?.resetNetwork()
            failureCounter.set(0)
            lastSuccessfulResetAtMs.set(SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            failureCounter.incrementAndGet()
            Log.w(TAG, "Reset failed (reason=$reason)", e)
        }
    }

    fun cancelPendingReset() {
        resetJob?.cancel()
        resetJob = null
    }

    fun cleanup() {
        cancelPendingReset()
        callbacks = null
    }
}
