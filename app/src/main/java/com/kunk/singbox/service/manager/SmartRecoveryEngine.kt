package com.kunk.singbox.service.manager

import android.content.Context
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.VpnStateStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        // 决策阈值
        private const val DECISION_THRESHOLD = 70

        // 最小恢复间隔（毫秒）
        private const val MIN_RECOVERY_INTERVAL_MS = 10000L

        // 最大恢复频率（每分钟）
        private const val MAX_RECOVERY_PER_MINUTE = 6

        // 流量模式检测阈值
        private const val TRAFFIC_STALL_THRESHOLD_MS = 10_000L // 10秒无下载流量视为可能僵死
        private const val QUIC_TRAFFIC_STALL_THRESHOLD_MS = 5_000L // QUIC协议更敏感

        // 2025-fix-v29: 降低 QUIC 流量异常检测阈值
        // 原阈值 50 字节太高，无法检测到 TG 等应用的微量流量异常
        private const val TRAFFIC_IMBALANCE_THRESHOLD_BYTES = 50L // TCP 默认阈值
        private const val QUIC_TRAFFIC_IMBALANCE_THRESHOLD_BYTES = 20L // QUIC 更敏感的阈值

        private const val TRAFFIC_CHECK_INTERVAL_MS = 5_000L // 每5秒检测一次流量

        // 2025-fix-v29: QUIC 主动探测配置
        private const val QUIC_PROACTIVE_PROBE_INTERVAL_MS = 15_000L // 后台每15秒探测一次
        private const val QUIC_PROBE_URL = "https://www.gstatic.com/generate_204"
        private const val QUIC_PROBE_TIMEOUT_MS = 3000

        @Volatile
        private var instance: SmartRecoveryEngine? = null

        fun getInstance(context: Context): SmartRecoveryEngine {
            return instance ?: synchronized(this) {
                instance ?: SmartRecoveryEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val connectionHealthMonitor = ConnectionHealthMonitor.getInstance(context)
    private val precisionRecoveryManager = PrecisionRecoveryManager.getInstance(context)
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

    // 决策历史
    private val decisionHistory = mutableListOf<DecisionRecord>()

    // 恢复时间戳记录
    private val recoveryTimestamps = mutableListOf<Long>()

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

        // 启动决策循环
        startDecisionLoop()

        // 启动流量模式监控
        startTrafficMonitor()
    }

    /**
     * 启动决策循环
     */
    private fun startDecisionLoop() {
        scope.launch {
            while (isActive) {
                try {
                    makeDecision()
                    delay(5000) // 每5秒决策一次
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Decision loop error", e)
                    delay(5000)
                }
            }
        }
    }

    /**
     * 做出恢复决策
     */
    private suspend fun makeDecision() {
        if (!VpnStateStore.getActive()) {
            return
        }

        _isDeciding.value = true

        try {
            // 获取健康状态
            val healthScore = connectionHealthMonitor.healthScore.value
            val allAppStates = connectionHealthMonitor.getAllAppStates()

            // 检测假死应用
            val staleApps = detectStaleApps(allAppStates)

            // 计算决策分数
            val decisionScore = calculateDecisionScore(healthScore, staleApps)

            // 做出决策
            val decision = when {
                decisionScore >= DECISION_THRESHOLD && shouldTriggerRecovery() -> {
                    Decision.RECOVER
                }
                decisionScore >= 50 -> {
                    Decision.WAIT
                }
                else -> {
                    Decision.IGNORE
                }
            }

            // 记录决策
            val record = DecisionRecord(
                timestamp = System.currentTimeMillis(),
                healthScore = healthScore,
                staleApps = staleApps,
                decision = decision,
                reason = getDecisionReason(decisionScore, decision)
            )
            decisionHistory.add(record)

            // 限制历史记录大小
            if (decisionHistory.size > 100) {
                decisionHistory.removeAt(0)
            }

            _decisionCount.value++

            // 执行决策
            when (decision) {
                Decision.RECOVER -> {
                    Log.i(TAG, "Decision: RECOVER - $staleApps")
                    triggerRecovery(staleApps)
                }
                Decision.WAIT -> {
                    Log.d(TAG, "Decision: WAIT - monitoring")
                }
                Decision.IGNORE -> {
                    Log.d(TAG, "Decision: IGNORE - no action needed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decision making failed", e)
        } finally {
            _isDeciding.value = false
        }
    }

    /**
     * 检测假死应用
     */
    private fun detectStaleApps(appStates: Map<String, ConnectionHealthMonitor.ConnectionState>): List<String> {
        val staleApps = mutableListOf<String>()

        appStates.values.forEach { state ->
            if (!state.isActive && state.connectionCount > 0) {
                staleApps.add(state.packageName)
            }
        }

        return staleApps
    }

    /**
     * 计算决策分数
     */
    private fun calculateDecisionScore(
        healthScore: Int,
        staleApps: List<String>
    ): Int {
        var score = 0

        // 健康度评分影响（健康度越低，分数越高）
        score += (100 - healthScore)

        // 假死应用数量影响
        score += staleApps.size * 20

        // 应用行为学习影响
        staleApps.forEach { packageName ->
            val behavior = appBehaviorData[packageName]
            if (behavior != null) {
                // 如果该应用经常假死，降低决策分数（避免过度恢复）
                if (behavior.staleCount > 5) {
                    score -= 10
                }
                // 如果该应用恢复后效果明显，提高决策分数
                if (behavior.recoveryCount > 0 && behavior.avgRecoveryInterval < 60000) {
                    score += 5
                }
            }
        }

        return score.coerceIn(0, 100)
    }

    /**
     * 判断是否应该触发恢复
     */
    private fun shouldTriggerRecovery(): Boolean {
        val currentTime = System.currentTimeMillis()

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

        return true
    }

    /**
     * 触发恢复
     */
    private fun triggerRecovery(staleApps: List<String>) {
        if (staleApps.isEmpty()) {
            return
        }

        Log.i(TAG, "Triggering recovery for: $staleApps")

        // 记录恢复时间戳
        recoveryTimestamps.add(System.currentTimeMillis())

        // 更新应用行为数据
        staleApps.forEach { packageName ->
            val behavior = appBehaviorData[packageName]
            val currentTime = System.currentTimeMillis()

            val newBehavior = if (behavior != null) {
                behavior.copy(
                    staleCount = behavior.staleCount + 1,
                    lastRecoveryTime = currentTime
                )
            } else {
                AppBehavior(
                    packageName = packageName,
                    totalChecks = 0,
                    staleCount = 1,
                    recoveryCount = 0,
                    avgRecoveryInterval = 0,
                    lastRecoveryTime = currentTime
                )
            }
            appBehaviorData[packageName] = newBehavior
        }

        // 执行恢复
        precisionRecoveryManager.recoverApps(staleApps)

        _recoveryTriggeredCount.value++
    }

    /**
     * 获取决策原因
     */
    private fun getDecisionReason(score: Int, decision: Decision): String {
        return when (decision) {
            Decision.RECOVER -> "High recovery score ($score >= $DECISION_THRESHOLD)"
            Decision.WAIT -> "Moderate score ($score), monitoring"
            Decision.IGNORE -> "Low score ($score), no action needed"
        }
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

        // 2025-fix-v29: 使用协议相关的阈值检测流量异常
        val isQUIC = BoxWrapperManager.isCurrentOutboundQUICBased()
        val imbalanceThreshold = if (isQUIC) {
            QUIC_TRAFFIC_IMBALANCE_THRESHOLD_BYTES
        } else {
            TRAFFIC_IMBALANCE_THRESHOLD_BYTES
        }

        // 异常模式检测：有显著上传但下载很少或没有
        // 场景：TG 发送消息请求但服务器无响应
        if (uploadDelta > imbalanceThreshold) {
            val stallStart = uploadOnlyStartTime.get()
            if (stallStart == 0L) {
                // 开始记录异常流量
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

    // ==================== 2025-fix-v29: QUIC 主动探测 ====================

    /**
     * 通知 App 进入后台
     * 对于 QUIC 协议，启动主动探测任务
     */
    fun onAppBackground() {
        isAppInBackground.set(true)
        if (BoxWrapperManager.isCurrentOutboundQUICBased()) {
            startQuicProactiveProbe()
        }
    }

    /**
     * 通知 App 返回前台
     * 停止主动探测任务
     */
    fun onAppForeground() {
        isAppInBackground.set(false)
        stopQuicProactiveProbe()
    }

    /**
     * 启动 QUIC 主动探测
     * 后台期间定期探测 VPN 隧道可用性，及时触发恢复
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun startQuicProactiveProbe() {
        if (quicProbeJob?.isActive == true) return

        Log.i(TAG, "[QUIC Probe] Starting proactive probe for background QUIC")
        quicProbeJob = scope.launch {
            delay(QUIC_PROACTIVE_PROBE_INTERVAL_MS) // 首次延迟
            runProbeLoop()
            Log.i(TAG, "[QUIC Probe] Probe loop ended")
        }
    }

    /**
     * QUIC 探测循环主体
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun runProbeLoop() {
        while (shouldContinueProbing()) {
            try {
                if (!BoxWrapperManager.isCurrentOutboundQUICBased()) {
                    Log.d(TAG, "[QUIC Probe] Not using QUIC, stopping probe")
                    break
                }
                handleProbeResult(performQuicProbe())
                delay(QUIC_PROACTIVE_PROBE_INTERVAL_MS)
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "[QUIC Probe] Error", e)
                delay(QUIC_PROACTIVE_PROBE_INTERVAL_MS)
            }
        }
    }

    private fun shouldContinueProbing(): Boolean {
        return isAppInBackground.get() && VpnStateStore.getActive()
    }

    private fun handleProbeResult(latency: Int) {
        if (latency >= 0) {
            lastProbeSuccessTime.set(System.currentTimeMillis())
            Log.d(TAG, "[QUIC Probe] Probe OK, latency=${latency}ms")
        } else {
            val timeSinceLastSuccess = System.currentTimeMillis() - lastProbeSuccessTime.get()
            Log.w(TAG, "[QUIC Probe] Probe FAILED, since last success=${timeSinceLastSuccess}ms")

            // 如果连续失败超过阈值，触发 QUIC 恢复
            if (timeSinceLastSuccess > QUIC_TRAFFIC_STALL_THRESHOLD_MS && shouldTriggerRecovery()) {
                Log.i(TAG, "[QUIC Probe] Triggering QUIC recovery due to probe failure")
                triggerTrafficStallRecovery(isQUIC = true)
                lastProbeSuccessTime.set(System.currentTimeMillis()) // 重置，避免连续触发
            }
        }
    }

    /**
     * 停止 QUIC 主动探测
     */
    private fun stopQuicProactiveProbe() {
        quicProbeJob?.cancel()
        quicProbeJob = null
        Log.d(TAG, "[QUIC Probe] Stopped")
    }

    /**
     * 执行单次 QUIC 探测
     * @return 延迟毫秒数，-1 表示失败
     */
    private fun performQuicProbe(): Int {
        return try {
            // 使用当前选中的节点进行 URL 测试
            val selected = BoxWrapperManager.getSelectedOutbound() ?: return -1
            BoxWrapperManager.urlTestOutbound(selected, QUIC_PROBE_URL, QUIC_PROBE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "[QUIC Probe] Probe exception", e)
            -1
        }
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
    }
}
