package com.kunk.singbox.service.manager

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.SettingsRepository
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 路由组自动选择管理器
 * 负责定期为路由规则使用的 Selector 选择最低延迟节点。
 *
 * 设计目标：
 * 1. 不依赖机场是否提供规范的 url-test 分组
 * 2. 优先驱动系统内置 AUTO_ALL 分组，实现全量节点自动选优
 * 3. 使用切换阈值 + 冷却窗口，避免频繁抖动切换
 */
class RouteGroupSelector(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "RouteGroupSelector"
        private const val PROXY_SELECTOR_TAG = "PROXY"
        private const val AUTO_ALL_SELECTOR_TAG = "AUTO_ALL"
        private const val INITIAL_DELAY_MS = 2500L
        private const val DISABLED_RECHECK_MS = 5000L
        private const val LATENCY_TEST_TIMEOUT_MS = 5000L
        private const val MAX_CONCURRENT_TESTS = 4

        private const val DEFAULT_AUTO_SELECT_INTERVAL_MINUTES = 10
        private const val DEFAULT_TOLERANCE_MS = 30
        private const val DEFAULT_MANUAL_LOCK_MINUTES = 30

        private const val SWITCH_COOLDOWN_MS = 90_000L
        private const val FALLBACK_BAD_LATENCY_BASE_MS = 1200L
    }

    private val gson = Gson()
    private val settingsRepository by lazy { SettingsRepository.getInstance(context) }
    private var autoSelectJob: Job? = null
    private val lastSwitchAtByGroup = ConcurrentHashMap<String, Long>()

    private data class AutoSelectOptions(
        val intervalMs: Long,
        val toleranceMs: Long,
        val manualLockMs: Long,
        val fallbackMode: Boolean
    )

    interface Callbacks {
        val isRunning: Boolean
        val isStopping: Boolean
        fun getCommandClient(): CommandClient?
        fun getSelectedOutbound(groupTag: String): String?
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * 启动路由组自动选择
     */
    fun start(configContent: String) {
        stop()

        autoSelectJob = serviceScope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive && callbacks?.isRunning == true && callbacks?.isStopping != true) {
                val options = loadAutoSelectOptions()
                if (options == null) {
                    delay(DISABLED_RECHECK_MS)
                    continue
                }

                runCatching {
                    selectBestForRouteGroups(configContent, options)
                }.onFailure { e ->
                    Log.w(TAG, "Auto select tick failed: ${e.message}", e)
                }
                delay(options.intervalMs)
            }
        }
    }

    /**
     * 停止路由组自动选择
     */
    fun stop() {
        autoSelectJob?.cancel()
        autoSelectJob = null
    }

    /**
     * 为路由规则引用的 Selector 选择最低延迟节点
     */
    private suspend fun selectBestForRouteGroups(
        configContent: String,
        options: AutoSelectOptions
    ) {
        if (isManualLockActive(options)) {
            return
        }

        val cfg = runCatching { gson.fromJson(configContent, SingBoxConfig::class.java) }.getOrNull() ?: return
        val outbounds = cfg.outbounds.orEmpty()
        if (outbounds.isEmpty()) return
        val byTag = outbounds.associateBy { it.tag }
        val targetSelectors = collectTargetSelectors(cfg, outbounds)
        if (targetSelectors.isEmpty()) return

        val client = waitForCommandClient(LATENCY_TEST_TIMEOUT_MS) ?: return
        val core = SingBoxCore.getInstance(context)
        val semaphore = Semaphore(permits = MAX_CONCURRENT_TESTS)

        ensureProxyUsesAutoAllIfNeeded(client)

        for (selector in targetSelectors) {
            if (callbacks?.isRunning != true || callbacks?.isStopping == true) return

            val groupTag = selector.tag
            val candidates = selector.outbounds
                .orEmpty()
                .filter { it.isNotBlank() }
                .filterNot { it.equals("direct", true) || it.equals("block", true) || it.equals("dns-out", true) }
                .filter { tag ->
                    val outbound = byTag[tag] ?: return@filter false
                    isLatencyCandidate(outbound)
                }

            if (candidates.isEmpty()) continue

            val results = ConcurrentHashMap<String, Long>()

            coroutineScope {
                candidates.map { tag ->
                    async(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            val outbound = byTag[tag] ?: return@async
                            val rtt = try {
                                core.testOutboundLatency(outbound, outbounds)
                            } catch (_: Exception) {
                                -1L
                            }
                            if (rtt > 0) {
                                results[tag] = rtt
                            }
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }

            val bestEntry = results.entries.minByOrNull { it.value } ?: continue
            val best = bestEntry.key
            val bestLatency = bestEntry.value
            val currentSelected = callbacks?.getSelectedOutbound(groupTag)
            if (!shouldSwitch(groupTag, currentSelected, best, bestLatency, results, options)) continue

            runCatching {
                if (selectOutbound(client, groupTag, best)) {
                    lastSwitchAtByGroup[groupTag] = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Auto selected group=$groupTag -> $best (${bestLatency}ms)")

                    // 当 AUTO_ALL 发生切换时，仅在 PROXY 已处于自动模式下保持其指向 AUTO_ALL。
                    if (groupTag.equals(AUTO_ALL_SELECTOR_TAG, ignoreCase = true)) {
                        ensureProxyUsesAutoAllIfNeeded(client)
                    }
                }
            }
        }
    }

    private fun collectTargetSelectors(cfg: SingBoxConfig, outbounds: List<Outbound>): List<Outbound> {
        val referencedOutbounds = cfg.route?.rules.orEmpty()
            .mapNotNull { it.outbound }
            .toSet()

        val selectorsByTag = LinkedHashMap<String, Outbound>()

        // 优先驱动系统自动分组，避免依赖订阅方的分组质量。
        outbounds.firstOrNull {
            it.type == "selector" && it.tag.equals(AUTO_ALL_SELECTOR_TAG, ignoreCase = true)
        }?.let { selectorsByTag[it.tag] = it }

        // 保留路由规则中实际引用的 selector 自动选优（排除 PROXY，避免覆盖手动选择）。
        outbounds
            .asSequence()
            .filter { it.type == "selector" }
            .filter { referencedOutbounds.contains(it.tag) }
            .filterNot { it.tag.equals(PROXY_SELECTOR_TAG, ignoreCase = true) }
            .forEach { selectorsByTag[it.tag] = it }

        // 兼容历史配置：如果没有 AUTO_ALL 且没有其他路由 selector，则回退到 PROXY。
        if (selectorsByTag.isEmpty()) {
            outbounds.firstOrNull {
                it.type == "selector" && it.tag.equals(PROXY_SELECTOR_TAG, ignoreCase = true)
            }?.let { selectorsByTag[it.tag] = it }
        }

        return selectorsByTag.values.toList()
    }

    private fun isLatencyCandidate(outbound: Outbound): Boolean {
        return when (outbound.type.lowercase()) {
            "selector", "urltest", "url-test", "direct", "block", "dns" -> false
            else -> true
        }
    }

    private fun shouldSwitch(
        groupTag: String,
        currentSelected: String?,
        bestTag: String,
        bestLatency: Long,
        measuredLatencies: Map<String, Long>,
        options: AutoSelectOptions
    ): Boolean {
        if (currentSelected.isNullOrBlank()) return true
        if (currentSelected.equals(bestTag, ignoreCase = true)) return false

        val currentLatency = measuredLatencies[currentSelected]
            ?: measuredLatencies.entries.firstOrNull { it.key.equals(currentSelected, ignoreCase = true) }?.value

        // 当前节点无有效测量值：仅在回退模式开启时才执行自动切换。
        if (currentLatency == null || currentLatency <= 0) {
            return options.fallbackMode
        }

        val elapsedSinceLastSwitch = SystemClock.elapsedRealtime() - (lastSwitchAtByGroup[groupTag] ?: 0L)
        val improvement = currentLatency - bestLatency
        if (improvement <= 0L) return false
        val isInCooldown = elapsedSinceLastSwitch < SWITCH_COOLDOWN_MS
        val toleranceMs = options.toleranceMs.coerceAtLeast(1L)

        if (isInCooldown) {
            // 回退模式下，当当前节点延迟已经明显恶化时允许提前切换。
            if (!options.fallbackMode) return false
            val badThreshold = max(FALLBACK_BAD_LATENCY_BASE_MS, toleranceMs * 5L)
            return currentLatency >= badThreshold && improvement >= toleranceMs
        }

        return improvement >= toleranceMs
    }

    private fun isManualLockActive(options: AutoSelectOptions): Boolean {
        if (options.manualLockMs <= 0L) return false

        val lastManualSwitchAt = VpnStateStore.getLastManualNodeSwitchAtMs()
        if (lastManualSwitchAt <= 0L) return false

        val now = System.currentTimeMillis()
        val lockedUntil = lastManualSwitchAt + options.manualLockMs
        if (now < lockedUntil) {
            val remainingSec = ((lockedUntil - now) / 1000L).coerceAtLeast(0L)
            Log.d(TAG, "Manual lock active, skip auto select (remaining=${remainingSec}s)")
            return true
        }
        return false
    }

    private fun loadAutoSelectOptions(): AutoSelectOptions? {
        settingsRepository.reloadFromStorage()
        val settings = settingsRepository.settings.value
        if (!settings.autoSelectByLatency) return null

        val intervalMinutes = settings.autoSelectIntervalMinutes
            .takeIf { it > 0 }
            ?: DEFAULT_AUTO_SELECT_INTERVAL_MINUTES
        val toleranceMs = settings.autoSelectToleranceMs
            .takeIf { it > 0 }
            ?: DEFAULT_TOLERANCE_MS
        val manualLockMinutes = settings.autoSelectManualLockMinutes
            .takeIf { it >= 0 }
            ?: DEFAULT_MANUAL_LOCK_MINUTES

        return AutoSelectOptions(
            intervalMs = intervalMinutes.toLong().coerceIn(1L, 120L) * 60_000L,
            toleranceMs = toleranceMs.toLong().coerceIn(5L, 2_000L),
            manualLockMs = manualLockMinutes.toLong().coerceIn(0L, 180L) * 60_000L,
            fallbackMode = settings.autoSelectFallbackMode
        )
    }

    private fun ensureProxyUsesAutoAllIfNeeded(client: CommandClient) {
        val currentProxySelected = callbacks?.getSelectedOutbound(PROXY_SELECTOR_TAG)
        val shouldKeepAutoMode = currentProxySelected.isNullOrBlank() ||
            currentProxySelected.equals(AUTO_ALL_SELECTOR_TAG, ignoreCase = true)

        if (!shouldKeepAutoMode) return
        if (currentProxySelected.equals(AUTO_ALL_SELECTOR_TAG, ignoreCase = true)) return

        if (selectOutbound(client, PROXY_SELECTOR_TAG, AUTO_ALL_SELECTOR_TAG)) {
            Log.i(TAG, "Auto mode enabled: PROXY -> $AUTO_ALL_SELECTOR_TAG")
        }
    }

    private fun selectOutbound(client: CommandClient, groupTag: String, outboundTag: String): Boolean {
        return runCatching {
            try {
                client.selectOutbound(groupTag, outboundTag)
            } catch (_: Exception) {
                client.selectOutbound(groupTag.lowercase(), outboundTag)
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "selectOutbound failed: $groupTag -> $outboundTag (${e.message})")
            false
        }
    }

    private suspend fun waitForCommandClient(timeoutMs: Long): CommandClient? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val c = callbacks?.getCommandClient()
            if (c != null) return c
            delay(120)
        }
        return callbacks?.getCommandClient()
    }

    fun cleanup() {
        stop()
        lastSwitchAtByGroup.clear()
        callbacks = null
    }
}
