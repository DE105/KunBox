package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NodesViewModel(application: Application) : AndroidViewModel(application) {
    
    enum class SortType {
        DEFAULT, LATENCY, NAME, REGION
    }
    
    private val configRepository = ConfigRepository.getInstance(application)

    private var testingJob: Job? = null

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()
    
    // 正在测试延迟的节点 ID 集合
    private val _testingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val testingNodeIds: StateFlow<Set<String>> = _testingNodeIds.asStateFlow()
    
    private val _sortType = MutableStateFlow(SortType.DEFAULT)
    val sortType: StateFlow<SortType> = _sortType.asStateFlow()

    val nodes: StateFlow<List<NodeUi>> = combine(configRepository.nodes, _sortType) { nodes, sortType ->
        when (sortType) {
            SortType.DEFAULT -> nodes
            SortType.LATENCY -> nodes.sortedWith(compareBy<NodeUi> { it.latencyMs == null }.thenBy { it.latencyMs })
            SortType.NAME -> nodes.sortedBy { it.name }
            SortType.REGION -> nodes.sortedBy { it.regionFlag ?: "\uFFFF" } // Put no flag at end
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val nodeGroups: StateFlow<List<String>> = configRepository.nodeGroups
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = listOf("全部")
        )

    val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allNodeGroups: StateFlow<List<String>> = configRepository.allNodeGroups
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _switchResult = MutableStateFlow<String?>(null)
    val switchResult: StateFlow<String?> = _switchResult.asStateFlow()

    // 单节点测速反馈信息（仅在失败/超时时提示）
    private val _latencyMessage = MutableStateFlow<String?>(null)
    val latencyMessage: StateFlow<String?> = _latencyMessage.asStateFlow()

    // 添加节点结果反馈
    private val _addNodeResult = MutableStateFlow<String?>(null)
    val addNodeResult: StateFlow<String?> = _addNodeResult.asStateFlow()

    fun setActiveNode(nodeId: String) {
        viewModelScope.launch {
            val node = nodes.value.find { it.id == nodeId }
            val success = configRepository.setActiveNode(nodeId)
            if (SingBoxRemote.isRunning.value && node != null) {
                _switchResult.value = if (success) {
                    "已切换到 ${node.name}"
                } else {
                    "切换到 ${node.name} 失败"
                }
            }
        }
    }

    fun clearSwitchResult() {
        _switchResult.value = null
    }
    
    fun testLatency(nodeId: String) {
        if (_testingNodeIds.value.contains(nodeId)) return
        viewModelScope.launch {
            _testingNodeIds.value = _testingNodeIds.value + nodeId
            try {
                val node = nodes.value.find { it.id == nodeId }
                val latency = configRepository.testNodeLatency(nodeId)
                if (latency <= 0) {
                    _latencyMessage.value = "${node?.displayName ?: "该节点"} 测速失败/超时"
                }
            } finally {
                _testingNodeIds.value = _testingNodeIds.value - nodeId
            }
        }
    }

    fun clearLatencyMessage() {
        _latencyMessage.value = null
    }

    fun clearAddNodeResult() {
        _addNodeResult.value = null
    }

    fun testAllLatency() {
        if (_isTesting.value) {
            // 如果正在测试，则取消测试
            testingJob?.cancel()
            testingJob = null
            _isTesting.value = false
            _testingNodeIds.value = emptySet()
            return
        }
        
        testingJob = viewModelScope.launch {
            _isTesting.value = true
            try {
                // 使用 ConfigRepository 的批量测试功能，它已经在内部实现了并行化
                configRepository.testAllNodesLatency()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isTesting.value = false
                _testingNodeIds.value = emptySet()
                testingJob = null
            }
        }
    }

    fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            configRepository.deleteNode(nodeId)
        }
    }

    fun exportNode(nodeId: String): String? {
        return configRepository.exportNode(nodeId)
    }
    
    fun setSortType(type: SortType) {
        _sortType.value = type
    }
    
    fun clearLatency() {
        viewModelScope.launch {
            configRepository.clearAllNodesLatency()
        }
    }
    
    fun addNode(content: String) {
        viewModelScope.launch {
            val trimmedContent = content.trim()
            
            // 检查是否是支持的节点链接格式
            val supportedPrefixes = listOf(
                "vmess://", "vless://", "ss://", "trojan://",
                "hysteria2://", "hy2://", "hysteria://",
                "tuic://", "anytls://", "wireguard://", "ssh://"
            )
            
            if (supportedPrefixes.none { trimmedContent.startsWith(it) }) {
                _addNodeResult.value = "不支持的链接格式"
                return@launch
            }
            
            val result = configRepository.addSingleNode(trimmedContent)
            result.onSuccess { node ->
                _addNodeResult.value = "已添加节点: ${node.displayName}"
            }.onFailure { e ->
                _addNodeResult.value = e.message ?: "添加失败"
            }
        }
    }
}
