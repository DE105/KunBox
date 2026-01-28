package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Single entry point for all recovery/reset/restart operations.
 *
 * Goals (stability-first):
 * - Serialize operations (no concurrent recover/reset/restart calls into libbox)
 * - Coalesce bursts (screen on + foreground + network change, etc.) into one action
 * - Apply cooldown to the most disruptive operations (restart/deep)
 * - Provide consistent observability
 */
class RecoveryCoordinator(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RecoveryCoordinator"

        private const val COALESCE_WINDOW_MS = 2000L
        private const val RESTART_COOLDOWN_MS = 120000L
        // 2025-fix-v10: 从 30 秒缩短到 10 秒
        // 原来 30 秒太长，导致从 Doze 恢复后如果第一次 deep recovery 失败，需要等很久才能重试
        private const val DEEP_COOLDOWN_MS = 10000L
        // 2025-fix-v17: NetworkBump 冷却时间从 10 秒缩短到 2 秒
        // 因为 NetworkBump 是轻量级操作，不需要太长冷却期
        // 这样可以更快响应多次前台恢复（如快速切换应用）
        private const val NETWORK_BUMP_COOLDOWN_MS = 2000L

        private const val MAX_REASON_LEN = 240

        // 2025-fix-v10: 这些 reason 关键词可以绕过 deep recovery 冷却期
        // 当系统退出 Doze 模式或用户主动返回前台时，恢复网络是最高优先级
        private val COOLDOWN_EXEMPT_KEYWORDS = listOf("doze_exit", "app_foreground", "screen_on")
    }

    interface Callbacks {
        val isRunning: Boolean
        val isStopping: Boolean

        suspend fun recoverNetwork(mode: Int, reason: String): Boolean
        suspend fun enterDeviceIdle(reason: String): Boolean
        suspend fun resetConnectionsOptimal(reason: String, skipDebounce: Boolean)
        suspend fun resetCoreNetwork(reason: String, force: Boolean)
        suspend fun restartVpnService(reason: String)

        suspend fun closeIdleConnections(maxIdleSeconds: Int, reason: String): Int

        /**
         * 执行网络闪断（Network Bump）
         * 通过短暂改变底层网络设置，触发应用重建连接
         * 这是解决"后台恢复后应用一直加载中"问题的根治方案
         */
        suspend fun performNetworkBump(reason: String): Boolean

        fun addLog(message: String)
    }

    private var callbacks: Callbacks? = null

    private val lastRestartAtMs = AtomicLong(0L)
    private val lastDeepAtMs = AtomicLong(0L)
    private val lastNetworkBumpAtMs = AtomicLong(0L)

    private val stateLock = Any()
    private var pending: Request? = null
    private var worker: Job? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun cleanup() {
        synchronized(stateLock) {
            pending = null
            worker?.cancel()
            worker = null
        }
        callbacks = null
    }

    /**
     * Enqueue a recovery request. Requests are coalesced and executed serially.
     */
    fun request(req: Request) {
        val now = SystemClock.elapsedRealtime()
        val startWorker: Boolean
        synchronized(stateLock) {
            pending = when (val current = pending) {
                null -> req
                else -> merge(current, req, now)
            }
            startWorker = worker?.isActive != true
            if (startWorker) {
                worker = scope.launch {
                    drainLoop()
                }
            }
        }
    }

    private suspend fun drainLoop() {
        while (true) {
            val req = synchronized(stateLock) {
                val r = pending
                pending = null
                r
            } ?: return

            execute(req)
        }
    }

    @Suppress("CognitiveComplexMethod")
    private fun merge(a: Request, b: Request, nowMs: Long): Request {
        // Within a short window, merge aggressively.
        val withinWindow = (nowMs - a.requestedAtMs) <= COALESCE_WINDOW_MS ||
            (nowMs - b.requestedAtMs) <= COALESCE_WINDOW_MS
        if (!withinWindow) {
            // Not a burst: keep the earlier request and let the later one be handled next.
            // We still keep single pending slot; prefer higher priority to reduce churn.
        }

        // If doze-enter is involved, always prefer it (stability-first: stop churn).
        if (a is Request.EnterDeviceIdle || b is Request.EnterDeviceIdle) {
            val chosen = if (b.priority > a.priority) b else a
            val other = if (chosen === b) a else b
            return chosen.withReason(mergeReason(chosen.reason, other.reason))
        }

        // If both are reset-style requests, keep BOTH (common during network switching).
        val mergedReason = mergeReason(a.reason, b.reason)
        val resetComposite = mergeResetRequests(a, b, mergedReason)
        if (resetComposite != null) return resetComposite

        val chosen = if (b.priority > a.priority) b else a
        val other = if (chosen === b) a else b

        return chosen.withReason(mergeReason(chosen.reason, other.reason))
    }

    private fun mergeResetRequests(a: Request, b: Request, mergedReason: String): Request? {
        if (!isResetish(a) || !isResetish(b)) return null

        val items = mutableListOf<Request>()
        fun addReset(req: Request) {
            when (req) {
                is Request.ResetConnections,
                is Request.ResetCoreNetwork -> items.add(req)
                is Request.Composite -> {
                    req.items.forEach { addReset(it) }
                }
                else -> Unit
            }
        }

        addReset(a)
        addReset(b)

        val hasResetConnections = items.any { it is Request.ResetConnections }
        val hasResetCoreNetwork = items.any { it is Request.ResetCoreNetwork }
        if (!hasResetConnections || !hasResetCoreNetwork) return null

        // De-dup: keep one of each type (prefer the higher-priority ResetCoreNetwork if multiple).
        val core = items.filterIsInstance<Request.ResetCoreNetwork>()
            .maxByOrNull { it.priority }
        val conn = items.filterIsInstance<Request.ResetConnections>()
            .maxByOrNull { it.priority }
        if (core == null || conn == null) return null

        return Request.Composite(
            items = listOf(core, conn),
            reason = mergedReason,
            requestedAtMs = minOf(core.requestedAtMs, conn.requestedAtMs)
        )
    }

    private fun isResetish(req: Request): Boolean {
        return when (req) {
            is Request.ResetConnections,
            is Request.ResetCoreNetwork -> true
            is Request.Composite -> req.items.all {
                it is Request.ResetConnections || it is Request.ResetCoreNetwork
            }
            else -> false
        }
    }

    private fun mergeReason(primary: String, secondary: String): String {
        if (secondary.isBlank() || secondary == primary) return primary
        val merged = "$primary | $secondary"
        return if (merged.length <= MAX_REASON_LEN) merged else merged.take(MAX_REASON_LEN)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    private suspend fun execute(req: Request) {
        if (req is Request.Composite) {
            // Execute in descending priority order (e.g. reset network first, then close connections).
            req.items.sortedByDescending { it.priority }.forEach { execute(it) }
            return
        }
        val cb = callbacks ?: return

        // Gate: avoid touching core when not running/stopping.
        // Restart is handled separately below.
        if (req !is Request.Restart && (!cb.isRunning || cb.isStopping)) {
            Log.d(
                TAG,
                "Skip ${req.javaClass.simpleName}: running=${cb.isRunning} " +
                    "stopping=${cb.isStopping} reason=${req.reason}"
            )
            return
        }

        if (!checkCooldown(req, cb)) return

        performRequest(req, cb)
    }

    private fun checkCooldown(req: Request, cb: Callbacks): Boolean {
        val now = SystemClock.elapsedRealtime()
        return when (req) {
            is Request.Restart -> checkRestartCooldown(req, cb, now)
            is Request.Recover -> checkRecoverCooldown(req, cb, now)
            is Request.NetworkBump -> checkNetworkBumpCooldown(req, cb, now)
            else -> true
        }
    }

    private fun checkRestartCooldown(req: Request.Restart, cb: Callbacks, now: Long): Boolean {
        if (!cb.isRunning || cb.isStopping) {
            Log.w(
                TAG,
                "Skip restart: running=${cb.isRunning} stopping=${cb.isStopping} " +
                    "reason=${req.reason}"
            )
            cb.addLog("INFO [Recovery] restart skipped (state) reason=${req.reason}")
            return false
        }
        val last = lastRestartAtMs.get()
        if (now - last < RESTART_COOLDOWN_MS) {
            Log.w(TAG, "Restart in cooldown, skip. reason=${req.reason}")
            cb.addLog("INFO [Recovery] restart skipped (cooldown) reason=${req.reason}")
            return false
        }
        lastRestartAtMs.set(now)
        return true
    }

    private fun checkRecoverCooldown(req: Request.Recover, cb: Callbacks, now: Long): Boolean {
        if (req.mode == 3) {
            // 2025-fix-v10: 检查是否有冷却豁免
            val isExempt = COOLDOWN_EXEMPT_KEYWORDS.any { keyword ->
                req.reason.contains(keyword, ignoreCase = true)
            }

            if (!isExempt) {
                val last = lastDeepAtMs.get()
                if (now - last < DEEP_COOLDOWN_MS) {
                    Log.w(TAG, "Deep recovery in cooldown, skip. reason=${req.reason}")
                    cb.addLog("INFO [Recovery] deep skipped (cooldown) reason=${req.reason}")
                    return false
                }
            } else {
                Log.i(TAG, "Deep recovery cooldown exempt for reason=${req.reason}")
            }
            lastDeepAtMs.set(now)
        }
        return true
    }

    private fun checkNetworkBumpCooldown(req: Request.NetworkBump, cb: Callbacks, now: Long): Boolean {
        val isExempt = COOLDOWN_EXEMPT_KEYWORDS.any { keyword ->
            req.reason.contains(keyword, ignoreCase = true)
        }

        if (!isExempt) {
            val last = lastNetworkBumpAtMs.get()
            if (now - last < NETWORK_BUMP_COOLDOWN_MS) {
                Log.d(TAG, "NetworkBump in cooldown, skip. reason=${req.reason}")
                return false
            }
        }
        lastNetworkBumpAtMs.set(now)
        return true
    }

    private suspend fun performRequest(req: Request, cb: Callbacks) {
        val start = SystemClock.elapsedRealtime()
        try {
            when (req) {
                is Request.Recover -> {
                    val ok = cb.recoverNetwork(req.mode, req.reason)
                    cb.addLog(
                        "INFO [Recovery] recover(mode=${req.mode}) ok=$ok " +
                            "cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}"
                    )
                }

                is Request.EnterDeviceIdle -> {
                    val ok = cb.enterDeviceIdle(req.reason)
                    cb.addLog(
                        "INFO [Recovery] enterDeviceIdle ok=$ok " +
                            "cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}"
                    )
                }

                is Request.ResetConnections -> {
                    cb.resetConnectionsOptimal(req.reason, req.skipDebounce)
                    cb.addLog(
                        "INFO [Recovery] resetConnections(skipDebounce=${req.skipDebounce}) " +
                            "cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}"
                    )
                }

                is Request.ResetCoreNetwork -> {
                    cb.resetCoreNetwork(req.reason, req.force)
                    cb.addLog(
                        "INFO [Recovery] resetCoreNetwork(force=${req.force}) " +
                            "cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}"
                    )
                }

                is Request.CloseIdleConnections -> {
                    val count = cb.closeIdleConnections(req.maxIdleSeconds, req.reason)
                    if (count > 0) {
                        cb.addLog(
                            "INFO [Recovery] closeIdle(maxIdle=${req.maxIdleSeconds}s) closed=$count " +
                                "cost=${SystemClock.elapsedRealtime() - start}ms"
                        )
                    }
                }

                is Request.Composite -> {
                    for (item in req.items) {
                        performRequest(item, cb)
                    }
                }

                is Request.Restart -> {
                    cb.restartVpnService(req.reason)
                }

                is Request.NetworkBump -> {
                    val ok = cb.performNetworkBump(req.reason)
                    cb.addLog(
                        "INFO [Recovery] networkBump ok=$ok " +
                            "cost=${SystemClock.elapsedRealtime() - start}ms reason=${req.reason}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${req.javaClass.simpleName}", e)
            cb.addLog("WARN [Recovery] ${req.javaClass.simpleName} failed: ${e.message}")
        }
    }

    sealed interface Request {
        val priority: Int
        val reason: String
        val requestedAtMs: Long

        fun withReason(reason: String): Request

        data class Recover(
            val mode: Int,
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = when (mode) {
                3 -> 90 // deep
                2 -> 80 // full
                4 -> 70 // proactive
                1 -> 60 // quick
                else -> 55 // auto
            }

            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class ResetConnections(
            override val reason: String,
            val skipDebounce: Boolean,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = 40
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class ResetCoreNetwork(
            override val reason: String,
            val force: Boolean,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = if (force) 50 else 45
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class Restart(
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = 100
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class EnterDeviceIdle(
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            // High priority: entering doze should override most resets/recoveries.
            override val priority: Int = 95
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class Composite(
            val items: List<Request>,
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = items.maxOf { it.priority }
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class CloseIdleConnections(
            val maxIdleSeconds: Int,
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = 20
            override fun withReason(reason: String): Request = copy(reason = reason)
        }

        data class NetworkBump(
            override val reason: String,
            override val requestedAtMs: Long = SystemClock.elapsedRealtime()
        ) : Request {
            override val priority: Int = 85
            override fun withReason(reason: String): Request = copy(reason = reason)
        }
    }
}
