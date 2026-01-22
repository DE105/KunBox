package com.kunk.singbox.viewmodel

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.model.ConnectionStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 流量监控器
 *
 * 负责实时流量统计和速度计算
 * 支持双源统计：BoxWrapper 内核级 (优先) 和 TrafficStats 系统级 (回退)
 */
class TrafficMonitor(private val scope: CoroutineScope) {

    private val _stats = MutableStateFlow(ConnectionStats(0, 0, 0, 0, 0))
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()

    private var monitorJob: Job? = null

    private var trafficBaseTxBytes: Long = 0
    private var trafficBaseRxBytes: Long = 0
    private var lastTrafficTxBytes: Long = 0
    private var lastTrafficRxBytes: Long = 0
    private var lastTrafficSampleAtElapsedMs: Long = 0
    private var wrapperBaseUpload: Long = 0
    private var wrapperBaseDownload: Long = 0
    private var lastUploadSpeed: Long = 0
    private var lastDownloadSpeed: Long = 0

    private data class TrafficData(val tx: Long, val rx: Long, val totalTx: Long, val totalRx: Long)

    @Suppress("CognitiveComplexMethod")
    fun start() {
        stop()

        lastUploadSpeed = 0
        lastDownloadSpeed = 0

        val uid = Process.myUid()
        val tx0 = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
        val rx0 = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
        trafficBaseTxBytes = tx0
        trafficBaseRxBytes = rx0
        lastTrafficTxBytes = tx0
        lastTrafficRxBytes = rx0
        lastTrafficSampleAtElapsedMs = SystemClock.elapsedRealtime()

        wrapperBaseUpload = BoxWrapperManager.getUploadTotal().let { if (it >= 0) it else 0L }
        wrapperBaseDownload = BoxWrapperManager.getDownloadTotal().let { if (it >= 0) it else 0L }

        monitorJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)

                val nowElapsed = SystemClock.elapsedRealtime()
                val data = collectTrafficData(uid)

                val dtMs = (nowElapsed - lastTrafficSampleAtElapsedMs).coerceAtLeast(1L)
                val dTx = (data.tx - lastTrafficTxBytes).coerceAtLeast(0L)
                val dRx = (data.rx - lastTrafficRxBytes).coerceAtLeast(0L)

                val up = (dTx * 1000L) / dtMs
                val down = (dRx * 1000L) / dtMs

                val uploadSmoothFactor = calculateAdaptiveSmoothFactor(up, lastUploadSpeed)
                val downloadSmoothFactor = calculateAdaptiveSmoothFactor(down, lastDownloadSpeed)

                val smoothedUp = if (lastUploadSpeed == 0L) up
                else (lastUploadSpeed * (1 - uploadSmoothFactor) + up * uploadSmoothFactor).toLong()
                val smoothedDown = if (lastDownloadSpeed == 0L) down
                else (lastDownloadSpeed * (1 - downloadSmoothFactor) + down * downloadSmoothFactor).toLong()

                lastUploadSpeed = smoothedUp
                lastDownloadSpeed = smoothedDown

                _stats.update { current ->
                    current.copy(
                        uploadSpeed = smoothedUp,
                        downloadSpeed = smoothedDown,
                        uploadTotal = data.totalTx,
                        downloadTotal = data.totalRx
                    )
                }

                lastTrafficTxBytes = data.tx
                lastTrafficRxBytes = data.rx
                lastTrafficSampleAtElapsedMs = nowElapsed
            }
        }
    }

    private fun collectTrafficData(uid: Int): TrafficData {
        return if (BoxWrapperManager.isAvailable()) {
            val wrapperUp = BoxWrapperManager.getUploadTotal()
            val wrapperDown = BoxWrapperManager.getDownloadTotal()
            if (wrapperUp >= 0 && wrapperDown >= 0) {
                val sessionUp = (wrapperUp - wrapperBaseUpload).coerceAtLeast(0L)
                val sessionDown = (wrapperDown - wrapperBaseDownload).coerceAtLeast(0L)
                TrafficData(wrapperUp, wrapperDown, sessionUp, sessionDown)
            } else {
                collectFromTrafficStats(uid)
            }
        } else {
            collectFromTrafficStats(uid)
        }
    }

    private fun collectFromTrafficStats(uid: Int): TrafficData {
        val sysTx = TrafficStats.getUidTxBytes(uid).let { if (it > 0) it else 0L }
        val sysRx = TrafficStats.getUidRxBytes(uid).let { if (it > 0) it else 0L }
        return TrafficData(
            sysTx,
            sysRx,
            (sysTx - trafficBaseTxBytes).coerceAtLeast(0L),
            (sysRx - trafficBaseRxBytes).coerceAtLeast(0L)
        )
    }

    private fun calculateAdaptiveSmoothFactor(current: Long, previous: Long): Double {
        if (previous <= 0) return 1.0

        val change = kotlin.math.abs(current - previous).toDouble()
        val ratio = change / previous

        return when {
            ratio > 2.0 -> 0.7
            ratio > 0.5 -> 0.4
            ratio > 0.1 -> 0.25
            else -> 0.15
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        lastUploadSpeed = 0
        lastDownloadSpeed = 0
        trafficBaseTxBytes = 0
        trafficBaseRxBytes = 0
        lastTrafficTxBytes = 0
        lastTrafficRxBytes = 0
        lastTrafficSampleAtElapsedMs = 0
        wrapperBaseUpload = 0
        wrapperBaseDownload = 0
    }

    fun reset() {
        _stats.value = ConnectionStats(0, 0, 0, 0, 0)
    }

    fun updateDuration(durationMs: Long) {
        _stats.update { current ->
            current.copy(duration = durationMs)
        }
    }
}
