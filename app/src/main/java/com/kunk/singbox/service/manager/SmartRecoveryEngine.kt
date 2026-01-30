package com.kunk.singbox.service.manager

import android.content.Context
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.VpnStateStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 智能恢复引擎 - 完美方案第三层：决策性防御
 *
 * 功能：
 * 1. 智能决策何时触发恢复
 * 2. 避免误触发和过度恢复
 * 3. 学习应用行为模式
 * 4. 提供决策统计和日志
 *
 * 设计原则：
 * - 智能：基于历史数据做决策
 * - 准确：避免误报和漏报
 * - 自适应：根据实际情况调整策略
 */
class SmartRecoveryEngine private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "SmartRecoveryEngine"
        private const val MIN_RECOVERY_INTERVAL_MS = 60_000L
        private const val MAX_RECOVERY_PER_MINUTE = 2
        private const val TRAFFIC_STALL_THRESHOLD_MS = 120_000L
        private const val QUIC_TRAFFIC_STALL_THRESHOLD_MS = 60_000L
        private const val TRAFFIC_IMBALANCE_THRESHOLD_BYTES = 2000L
        private const val QUIC_TRAFFIC_IMBALANCE_THRESHOLD_BYTES = 1000L
        private const val TRAFFIC_CHECK_INTERVAL_MS = 30_000L

        @Volatile
        private var instance: SmartRecoveryEngine? = null

        fun getInstance(context: Context): SmartRecoveryEngine {
            return instance ?: synchronized(this) {
                instance ?: SmartRecoveryEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 决策状态
    private val _isDeciding = MutableStateFlow(false)
    val isDeciding: StateFlow<Boolean> = _isDeciding.asStateFlow()

    // 决策统计
    private val _decisionCount = MutableStateFlow(0)
    val decisionCount: StateFlow<Int> = _decisionCount.asStateFlow()

    private val _recoveryTriggeredCount = MutableStateFlow(0)
    val recoveryTriggeredCount: StateFlow<Int> = _recoveryTriggeredCount.asStateFlow()

    // 应用行为学习数据
    private val appBehaviorData = ConcurrentHashMap<String, AppBehavior>()

    // 决策历史 - 使用 synchronizedList 保证多协程并发访问安全
    private val decisionHistory: MutableList<DecisionRecord> =
        Collections.synchronizedList(mutableListOf())

    // 恢复时间戳记录 - 使用 synchronizedList 保证多协程并发访问安全
    private val recoveryTimestamps: MutableList<Long> =
        Collections.synchronizedList(mutableListOf())

    // 是否已初始化
    private val initialized = AtomicBoolean(false)

    // 流量模式检测状态
    private val lastUploadBytes = AtomicLong(0L)
    private val lastDownloadBytes = AtomicLong(0L)
    private val uploadOnlyStartTime = AtomicLong(0L) // 开始只有上传没下载的时间点

    // 定期任务
    private var trafficMonitorJob: Job? = null

    // 2025-fix-v29: QUIC 主动探测任务
    private var quicProbeJob: Job? = null
    private val isAppInBackground = AtomicBoolean(false)
    private val lastProbeSuccessTime = AtomicLong(System.currentTimeMillis())

    /**
     * 应用行为数据
     */
    data class AppBehavior(
        val packageName: String,
        val totalChecks: Int,
        val staleCount: Int,
        val recoveryCount: Int,
        val avgRecoveryInterval: Long,
        val lastRecoveryTime: Long
    )

    /**
     * 决策记录
     */
    data class DecisionRecord(
        val timestamp: Long,
        val healthScore: Int,
        val staleApps: List<String>,
        val decision: Decision,
        val reason: String
    )

    /**
     * 决策类型
     */
    enum class Decision {
        RECOVER,
        WAIT,
        IGNORE
    }

    /**
     * 初始化决策引擎
     */
    fun initialize() {
        if (initialized.getAndSet(true)) {
            Log.d(TAG, "Already initialized")
            return
        }

        Log.i(TAG, "Initializing SmartRecoveryEngine")
        startTrafficMonitor()
    }

    /**
     * 判断是否应该触发恢复
     */
    private fun shouldTriggerRecovery(): Boolean {
        val currentTime = System.currentTimeMillis()

        // synchronized 块保护 removeIf/size/last 等复合操作的原子性
        synchronized(recoveryTimestamps) {
            // 清理过期的恢复时间戳
            recoveryTimestamps.removeIf { currentTime - it > 60000 }

            // 检查恢复频率
            if (recoveryTimestamps.size >= MAX_RECOVERY_PER_MINUTE) {
                Log.d(TAG, "Recovery rate limit reached (${recoveryTimestamps.size}/min)")
                return false
            }

            // 检查最小恢复间隔
            if (recoveryTimestamps.isNotEmpty()) {
                val lastRecoveryTime = recoveryTimestamps.last()
                val timeSinceLastRecovery = currentTime - lastRecoveryTime
                if (timeSinceLastRecovery < MIN_RECOVERY_INTERVAL_MS) {
                    Log.d(TAG, "Recovery cooldown active (${timeSinceLastRecovery}ms < ${MIN_RECOVERY_INTERVAL_MS}ms)")
                    return false
                }
            }
        }

        return true
    }

    /**
     * 更新应用行为数据
     */
    @Suppress("CognitiveComplexMethod")
    fun updateAppBehavior(packageName: String, recovered: Boolean) {
        val behavior = appBehaviorData[packageName]
        val currentTime = System.currentTimeMillis()

        val newBehavior = if (behavior != null) {
            val newRecoveryCount = if (recovered) behavior.recoveryCount + 1 else behavior.recoveryCount
            val newAvgInterval = if (recovered && behavior.lastRecoveryTime > 0) {
                val interval = currentTime - behavior.lastRecoveryTime
                (behavior.avgRecoveryInterval * behavior.recoveryCount + interval) / (behavior.recoveryCount + 1)
            } else {
                behavior.avgRecoveryInterval
            }

            behavior.copy(
                totalChecks = behavior.totalChecks + 1,
                recoveryCount = newRecoveryCount,
                avgRecoveryInterval = newAvgInterval,
                lastRecoveryTime = if (recovered) currentTime else behavior.lastRecoveryTime
            )
        } else {
            AppBehavior(
                packageName = packageName,
                totalChecks = 1,
                staleCount = 0,
                recoveryCount = if (recovered) 1 else 0,
                avgRecoveryInterval = 0,
                lastRecoveryTime = if (recovered) currentTime else 0
            )
        }

        appBehaviorData[packageName] = newBehavior
    }

    /**
     * 获取应用行为数据
     */
    fun getAppBehavior(packageName: String): AppBehavior? {
        return appBehaviorData[packageName]
    }

    /**
     * 获取所有应用行为数据
     */
    fun getAllAppBehavior(): Map<String, AppBehavior> {
        return appBehaviorData.toMap()
    }

    /**
     * 获取决策历史
     */
    fun getDecisionHistory(): List<DecisionRecord> {
        return decisionHistory.toList()
    }

    /**
     * 启动流量模式监控
     * 检测单向流量模式（只有上传没下载），这是 TCP 连接僵死的典型特征
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun startTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = scope.launch {
            // 初始化流量基准
            lastUploadBytes.set(BoxWrapperManager.getUploadTotal().coerceAtLeast(0))
            lastDownloadBytes.set(BoxWrapperManager.getDownloadTotal().coerceAtLeast(0))

            while (isActive) {
                try {
                    delay(TRAFFIC_CHECK_INTERVAL_MS)
                    if (!VpnStateStore.getActive()) continue

                    checkTrafficPattern()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Traffic monitor error", e)
                }
            }
        }
    }

    /**
     * 检查流量模式
     * 检测两种异常模式：
     * 1. 完全单向流量：只有上传没有下载
     * 2. 严重不平衡：上传远大于下载（可能是请求发出去但响应很少）
     */
    @Suppress("CognitiveComplexMethod")
    private fun checkTrafficPattern() {
        val currentUpload = BoxWrapperManager.getUploadTotal()
        val currentDownload = BoxWrapperManager.getDownloadTotal()
        val now = System.currentTimeMillis()

        if (currentUpload < 0 || currentDownload < 0) return // 数据无效

        val prevUpload = lastUploadBytes.getAndSet(currentUpload)
        val prevDownload = lastDownloadBytes.getAndSet(currentDownload)

        val uploadDelta = currentUpload - prevUpload
        val downloadDelta = currentDownload - prevDownload

        // 有正常的下载流量（下载量大于上传量的 10%），重置检测状态
        if (downloadDelta > 0 && downloadDelta > uploadDelta / 10) {
            uploadOnlyStartTime.set(0)
            lastProbeSuccessTime.set(now) // 有正常流量，更新探测成功时间
            return
        }

        val isQUIC = BoxWrapperManager.isCurrentOutboundQUICBased()
        val imbalanceThreshold = if (isQUIC) {
            QUIC_TRAFFIC_IMBALANCE_THRESHOLD_BYTES
        } else {
            TRAFFIC_IMBALANCE_THRESHOLD_BYTES
        }

        if (uploadDelta > imbalanceThreshold) {
            val stallStart = uploadOnlyStartTime.get()
            if (stallStart == 0L) {
                uploadOnlyStartTime.set(now)
                Log.d(
                    TAG,
                    "[Traffic] Imbalanced: up=$uploadDelta, down=$downloadDelta, isQUIC=$isQUIC"
                )
            } else {
                val stallDuration = now - stallStart
                val threshold = if (isQUIC) QUIC_TRAFFIC_STALL_THRESHOLD_MS else TRAFFIC_STALL_THRESHOLD_MS
                if (stallDuration > threshold) {
                    Log.w(
                        TAG,
                        "[Traffic] Stall detected: imbalanced traffic for ${stallDuration}ms " +
                            "(isQUIC=$isQUIC, threshold=${threshold}ms), triggering recovery"
                    )
                    triggerTrafficStallRecovery(isQUIC)
                    uploadOnlyStartTime.set(0) // 重置
                }
            }
        }
    }

    /**
     * 触发流量停滞恢复
     */
    private fun triggerTrafficStallRecovery(isQUIC: Boolean = false) {
        if (!shouldTriggerRecovery()) {
            Log.d(TAG, "[Traffic] Recovery skipped (rate limit)")
            return
        }

        Log.i(TAG, "[Traffic] Triggering recovery due to traffic stall (isQUIC=$isQUIC)")
        recoveryTimestamps.add(System.currentTimeMillis())
        _recoveryTriggeredCount.value++

        if (isQUIC) {
            BoxWrapperManager.recoverNetworkForQUIC()
        } else {
            val closed = BoxWrapperManager.closeIdleConnections(30)
            Log.i(TAG, "[Traffic] Closed $closed idle connections")
        }
    }

    fun onAppBackground() {
        isAppInBackground.set(true)
    }

    fun onAppForeground() {
        isAppInBackground.set(false)
        quicProbeJob?.cancel()
        quicProbeJob = null
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up SmartRecoveryEngine")
        trafficMonitorJob?.cancel()
        quicProbeJob?.cancel()
        scope.cancel()
        appBehaviorData.clear()
        decisionHistory.clear()
        recoveryTimestamps.clear()
        uploadOnlyStartTime.set(0)
        isAppInBackground.set(false)
        initialized.set(false)
        // 重置单例，下次 getInstance 时创建新实例（含新 scope）
        instance = null
    }
}
