package com.kunk.singbox.core

import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.Outbound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 安全延迟测试器 - 保护主网络连接不受测试影响
 *
 * 核心策略 (v2.9.0 双层隔离):
 *
 * [Kotlin 层保护]
 * 1. NetworkGuard: 测试期间持续监控主连接，发现异常立即熔断
 * 2. AdaptiveRate: 根据网络状况动态调整并发数
 * 3. BatchThrottle: 批次间添加间隔，给主流量让路
 * 4. CircuitBreaker: 连续失败时暂停测试
 *
 * [Go 内核层隔离]
 * 5. IsolatedDialer: 测试连接添加让路延迟
 * 6. GlobalRateLimit: 内核层全局限制最大 3 并发
 * 7. MainTrafficYield: 检测到主流量时自动让路
 */
@Suppress("TooManyFunctions")
class SafeLatencyTester private constructor() {

    companion object {
        private const val TAG = "SafeLatencyTester"

        // 主连接保护参数
        private const val GUARD_PROBE_INTERVAL_MS = 500L // 每 500ms 探测一次主连接
        private const val GUARD_LATENCY_THRESHOLD_MS = 2000 // 主连接延迟超过 2s 视为异常
        private const val GUARD_FAIL_THRESHOLD = 2 // 连续 2 次探测失败触发熔断

        // 自适应并发参数
        private const val MIN_CONCURRENCY = 1
        private const val MAX_CONCURRENCY = 5 // 降低最大并发，保护主连接
        private const val INITIAL_CONCURRENCY = 2 // 初始并发数

        // 批次控制参数
        private const val BATCH_SIZE = 10 // 每批测试节点数
        private const val BATCH_INTERVAL_MS = 300L // 批次间隔
        private const val YIELD_INTERVAL_MS = 50L // 每个测试后的让路时间

        // 熔断参数
        private const val CIRCUIT_BREAKER_THRESHOLD = 5 // 连续 5 次失败触发熔断
        private const val CIRCUIT_BREAKER_COOLDOWN_MS = 5000L // 熔断冷却时间

        @Volatile
        private var instance: SafeLatencyTester? = null

        fun getInstance(): SafeLatencyTester {
            return instance ?: synchronized(this) {
                instance ?: SafeLatencyTester().also { instance = it }
            }
        }
    }

    // 状态追踪
    private val isTestingActive = AtomicBoolean(false)
    private val shouldAbort = AtomicBoolean(false)
    private val currentConcurrency = AtomicInteger(INITIAL_CONCURRENCY)
    private val consecutiveFailures = AtomicInteger(0)
    private val lastCircuitBreakerTrip = AtomicLong(0)

    // 主连接保护
    private var guardJob: Job? = null
    private val guardScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 安全的批量延迟测试 - 保护主网络连接
     *
     * @param outbounds 待测试的节点列表
     * @param targetUrl 测试 URL
     * @param timeoutMs 单节点超时时间
     * @param onResult 每个节点测试完成的回调
     */
    suspend fun testOutboundsLatencySafe(
        outbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        if (outbounds.isEmpty()) return

        if (isCircuitBreakerOpen()) {
            Log.w(TAG, "Circuit breaker is open, skipping test")
            outbounds.forEach { onResult(it.tag, -1L) }
            return
        }

        if (!isTestingActive.compareAndSet(false, true)) {
            Log.w(TAG, "Another test is in progress, skipping")
            outbounds.forEach { onResult(it.tag, -1L) }
            return
        }

        try {
            initTestState()
            Log.i(TAG, "Starting safe latency test: ${outbounds.size} nodes, " +
                "concurrency=$INITIAL_CONCURRENCY (adaptive)")
            if (VpnStateStore.getActive()) startNetworkGuard()
            processBatches(outbounds, targetUrl, timeoutMs, onResult)
        } finally {
            stopNetworkGuard()
            isTestingActive.set(false)
            Log.i(TAG, "Safe latency test completed")
        }
    }

    private fun initTestState() {
        shouldAbort.set(false)
        consecutiveFailures.set(0)
        currentConcurrency.set(INITIAL_CONCURRENCY)
    }

    private suspend fun processBatches(
        outbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        val batches = outbounds.chunked(BATCH_SIZE)
        for ((batchIndex, batch) in batches.withIndex()) {
            if (shouldAbort.get()) {
                Log.w(TAG, "Test aborted by guard")
                batch.forEach { onResult(it.tag, -1L) }
                continue
            }

            Log.d(TAG, "Testing batch ${batchIndex + 1}/${batches.size}, " +
                "concurrency=${currentConcurrency.get()}")

            testBatchSafe(batch, targetUrl, timeoutMs, onResult)

            if (batchIndex < batches.size - 1 && !shouldAbort.get()) {
                delay(BATCH_INTERVAL_MS)
            }
        }
    }

    /**
     * 测试单个批次
     */
    private suspend fun testBatchSafe(
        batch: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(currentConcurrency.get())

        val jobs = batch.map { outbound ->
            async {
                if (shouldAbort.get()) {
                    onResult(outbound.tag, -1L)
                    return@async
                }

                semaphore.withPermit {
                    if (shouldAbort.get()) {
                        onResult(outbound.tag, -1L)
                        return@withPermit
                    }

                    val startTime = System.currentTimeMillis()
                    val latency = testSingleNodeSafe(outbound.tag, targetUrl, timeoutMs)
                    val elapsed = System.currentTimeMillis() - startTime

                    // 自适应调整并发 - 始终启用
                    adjustConcurrency(latency, elapsed)

                    onResult(outbound.tag, latency)

                    // 让路
                    delay(YIELD_INTERVAL_MS)
                }
            }
        }

        jobs.awaitAll()
    }

    /**
     * 安全测试单个节点
     */
    private suspend fun testSingleNodeSafe(
        tag: String,
        url: String,
        timeoutMs: Int
    ): Long {
        if (!VpnStateStore.getActive() || !BoxWrapperManager.isAvailable()) {
            return -1L
        }

        return try {
            val result = withTimeoutOrNull(timeoutMs.toLong() + 1000) {
                BoxWrapperManager.urlTestOutbound(tag, url, timeoutMs)
            }

            if (result != null && result >= 0) {
                consecutiveFailures.set(0)
                result.toLong()
            } else {
                handleTestFailure()
                -1L
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Test failed for $tag: ${e.message}")
            handleTestFailure()
            -1L
        }
    }

    /**
     * 处理测试失败
     */
    private fun handleTestFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            tripCircuitBreaker()
        }
    }

    /**
     * 自适应调整并发数
     */
    private fun adjustConcurrency(latency: Long, elapsed: Long) {
        val current = currentConcurrency.get()

        when {
            // 测试很快且成功，可以增加并发
            latency > 0 && elapsed < 500 && current < MAX_CONCURRENCY -> {
                currentConcurrency.compareAndSet(current, current + 1)
            }
            // 测试较慢或失败，降低并发
            latency < 0 || elapsed > 2000 -> {
                if (current > MIN_CONCURRENCY) {
                    currentConcurrency.compareAndSet(current, current - 1)
                }
            }
        }
    }

    /**
     * 启动网络守护 - 持续监控主连接
     */
    @Suppress("CognitiveComplexMethod", "LoopWithTooManyJumpStatements")
    private fun startNetworkGuard() {
        guardJob?.cancel()
        guardJob = guardScope.launch {
            var consecutiveGuardFailures = 0

            while (isActive && isTestingActive.get()) {
                delay(GUARD_PROBE_INTERVAL_MS)

                if (!isTestingActive.get()) break

                val probeResult = probeMainConnection()

                if (probeResult < 0 || probeResult > GUARD_LATENCY_THRESHOLD_MS) {
                    consecutiveGuardFailures++
                    Log.w(TAG, "[Guard] Main connection degraded: ${probeResult}ms " +
                        "(failures: $consecutiveGuardFailures/$GUARD_FAIL_THRESHOLD)")

                    if (consecutiveGuardFailures >= GUARD_FAIL_THRESHOLD) {
                        Log.e(TAG, "[Guard] ABORT - Main connection critically affected!")
                        shouldAbort.set(true)
                        currentConcurrency.set(MIN_CONCURRENCY)
                        break
                    }
                } else {
                    consecutiveGuardFailures = 0
                }
            }
        }
    }

    /**
     * 停止网络守护
     */
    private fun stopNetworkGuard() {
        guardJob?.cancel()
        guardJob = null
    }

    /**
     * 探测主连接 - 测试当前选中节点的延迟
     * 同时通知内核层主流量正在活跃，使测试连接让路
     */
    private fun probeMainConnection(): Int {
        if (!VpnStateStore.getActive() || !BoxWrapperManager.isAvailable()) {
            return -1
        }

        val selected = BoxWrapperManager.getSelectedOutbound()
        if (selected.isNullOrBlank()) {
            return -1
        }

        // 通知内核主流量活跃，让测试连接让路
        BoxWrapperManager.notifyMainTrafficActive()

        return try {
            // 使用短超时快速探测
            BoxWrapperManager.urlTestOutbound(
                selected,
                "https://www.gstatic.com/generate_204",
                1500 // 1.5s 超时
            )
        } catch (e: Exception) {
            Log.w(TAG, "[Guard] Probe failed: ${e.message}")
            -1
        }
    }

    /**
     * 检查熔断器状态
     */
    private fun isCircuitBreakerOpen(): Boolean {
        val lastTrip = lastCircuitBreakerTrip.get()
        if (lastTrip == 0L) return false

        val elapsed = System.currentTimeMillis() - lastTrip
        return elapsed < CIRCUIT_BREAKER_COOLDOWN_MS
    }

    /**
     * 触发熔断
     */
    private fun tripCircuitBreaker() {
        lastCircuitBreakerTrip.set(System.currentTimeMillis())
        shouldAbort.set(true)
        Log.e(TAG, "Circuit breaker tripped! Cooling down for ${CIRCUIT_BREAKER_COOLDOWN_MS}ms")
    }

    /**
     * 取消当前测试
     */
    fun cancelTest() {
        shouldAbort.set(true)
        stopNetworkGuard()
    }

    /**
     * 检查是否正在测试
     */
    fun isTesting(): Boolean = isTestingActive.get()

    /**
     * 获取当前并发数
     */
    fun getCurrentConcurrency(): Int = currentConcurrency.get()
}
