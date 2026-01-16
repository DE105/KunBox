package com.kunk.singbox.service.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 节点切换管理器
 * 负责热切换和下一节点切换逻辑
 */
class NodeSwitchManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "NodeSwitchManager"
    }

    interface Callbacks {
        val isRunning: Boolean
        suspend fun hotSwitchNode(nodeTag: String): Boolean
        fun getConfigPath(): String
        fun setRealTimeNodeName(name: String?)
        fun requestNotificationUpdate(force: Boolean)
        fun startServiceIntent(intent: Intent)
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * 执行热切换
     */
    fun performHotSwitch(
        nodeId: String,
        outboundTag: String?,
        serviceClass: Class<*>,
        actionStart: String,
        extraConfigPath: String
    ) {
        serviceScope.launch {
            val configRepository = ConfigRepository.getInstance(context)
            val node = configRepository.getNodeById(nodeId)

            val nodeTag = outboundTag ?: node?.name

            if (nodeTag == null) {
                Log.w(TAG, "Hot switch failed: node not found $nodeId and no outboundTag provided")
                return@launch
            }

            val success = callbacks?.hotSwitchNode(nodeTag) == true

            if (success) {
                Log.i(TAG, "Hot switch successful for $nodeTag")
                val displayName = node?.name ?: nodeTag
                callbacks?.setRealTimeNodeName(displayName)
                runCatching { configRepository.syncActiveNodeFromProxySelection(displayName) }
                callbacks?.requestNotificationUpdate(force = false)
            } else {
                Log.w(TAG, "Hot switch failed for $nodeTag, falling back to restart")
                val configPath = callbacks?.getConfigPath() ?: return@launch
                val restartIntent = Intent(context, serviceClass).apply {
                    action = actionStart
                    putExtra(extraConfigPath, configPath)
                }
                callbacks?.startServiceIntent(restartIntent)
            }
        }
    }

    /**
     * 切换到下一个节点
     */
    fun switchNextNode(
        serviceClass: Class<*>,
        actionStart: String,
        extraConfigPath: String
    ) {
        if (callbacks?.isRunning != true) {
            Log.w(TAG, "switchNextNode: VPN not running, skip")
            return
        }

        serviceScope.launch {
            val configRepository = ConfigRepository.getInstance(context)
            configRepository.reloadProfiles()

            val nodes = configRepository.nodes.value
            if (nodes.isEmpty()) {
                Log.w(TAG, "switchNextNode: no nodes available after reload")
                return@launch
            }

            val activeNodeId = configRepository.activeNodeId.value
            val currentIndex = nodes.indexOfFirst { it.id == activeNodeId }
            val nextIndex = (currentIndex + 1) % nodes.size
            val nextNode = nodes[nextIndex]

            Log.i(TAG, "switchNextNode: switching from ${nodes.getOrNull(currentIndex)?.name} to ${nextNode.name}")

            configRepository.setActiveNodeIdOnly(nextNode.id)

            val success = callbacks?.hotSwitchNode(nextNode.name) == true
            if (success) {
                callbacks?.setRealTimeNodeName(nextNode.name)
                runCatching { configRepository.syncActiveNodeFromProxySelection(nextNode.name) }
                callbacks?.requestNotificationUpdate(force = true)
                Log.i(TAG, "switchNextNode: hot switch successful")
            } else {
                Log.w(TAG, "switchNextNode: hot switch failed, falling back to restart")
                val configPath = callbacks?.getConfigPath() ?: return@launch
                val restartIntent = Intent(context, serviceClass).apply {
                    action = actionStart
                    putExtra(extraConfigPath, configPath)
                }
                callbacks?.startServiceIntent(restartIntent)
            }
        }
    }

    fun cleanup() {
        callbacks = null
    }
}
