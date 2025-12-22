# Bug 修复方案：在没有配置时无法直接添加单个节点

## 问题描述

用户报告：在没有配置的时候，无法直接在节点列表页面添加单个节点。

## 问题分析

### 当前代码流程

1. 用户在 `NodesScreen` 点击"添加节点"按钮
2. 输入节点链接（如 `vmess://...`）后确认
3. 调用 `NodesViewModel.addNode(content)`
4. `addNode()` 调用 `configRepository.importFromContent("手动添加", content)`
5. `importFromContent()` 创建一个全新的配置文件

### 问题根源

1. **配置重复创建**：每次调用 `addNode()` 都会创建一个新的"手动添加"配置，而不是将节点添加到已有的配置中。

2. **激活逻辑问题**：在 `importFromContent()` 中，只有当 `_activeProfileId.value == null` 时才会自动激活新配置。如果用户之前已经添加过节点（此时已有一个活跃的"手动添加"配置），新创建的配置不会被激活，导致新添加的节点不会显示。

3. **无用户反馈**：`addNode()` 没有处理 `importFromContent()` 的返回值，用户无法知道添加是否成功。

4. **解析器链问题**：`ClashYamlParser.canParse()` 使用 `contains("proxies:")` 判断，可能误匹配节点链接中的 Base64 编码内容。

### 代码位置

- [`NodesViewModel.addNode()`](../app/src/main/java/com/kunk/singbox/viewmodel/NodesViewModel.kt:170-189)
- [`ConfigRepository.importFromContent()`](../app/src/main/java/com/kunk/singbox/repository/ConfigRepository.kt:752-805)
- [`Base64Parser`](../app/src/main/java/com/kunk/singbox/utils/parser/CommonParsers.kt:33-101)
- [`NodeLinkParser`](../app/src/main/java/com/kunk/singbox/utils/parser/NodeLinkParser.kt)

## 修复方案

### 1. 在 ConfigRepository 中添加 `addSingleNode()` 方法

在 `ConfigRepository.kt` 中添加一个专门处理单个节点添加的方法：

```kotlin
/**
 * 添加单个节点
 * 如果存在"手动添加"配置，则将节点添加到该配置中
 * 如果不存在，则创建新的"手动添加"配置
 * 
 * @param link 节点链接（vmess://, vless://, ss://, etc）
 * @return 成功返回添加的节点，失败返回错误信息
 */
suspend fun addSingleNode(link: String): Result<NodeUi> = withContext(Dispatchers.IO) {
    try {
        // 1. 使用 NodeLinkParser 直接解析链接
        val nodeLinkParser = NodeLinkParser(gson)
        val outbound = nodeLinkParser.parse(link.trim())
            ?: return@withContext Result.failure(Exception("无法解析节点链接"))
        
        // 2. 查找或创建"手动添加"配置
        val manualProfileName = "手动添加"
        var manualProfile = _profiles.value.find { it.name == manualProfileName && it.type == ProfileType.Imported }
        val profileId: String
        val existingConfig: SingBoxConfig?
        
        if (manualProfile != null) {
            // 使用已有的"手动添加"配置
            profileId = manualProfile.id
            existingConfig = loadConfig(profileId)
        } else {
            // 创建新的"手动添加"配置
            profileId = UUID.randomUUID().toString()
            existingConfig = null
        }
        
        // 3. 合并或创建 outbounds
        val newOutbounds = mutableListOf<Outbound>()
        existingConfig?.outbounds?.let { existing ->
            // 添加现有的非系统 outbounds
            newOutbounds.addAll(existing.filter { it.type !in listOf("direct", "block", "dns") })
        }
        
        // 检查是否有同名节点，如有则添加后缀
        var finalTag = outbound.tag
        var counter = 1
        while (newOutbounds.any { it.tag == finalTag }) {
            finalTag = "${outbound.tag}_$counter"
            counter++
        }
        val finalOutbound = if (finalTag != outbound.tag) outbound.copy(tag = finalTag) else outbound
        newOutbounds.add(finalOutbound)
        
        // 添加系统 outbounds
        if (newOutbounds.none { it.tag == "direct" }) {
            newOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        if (newOutbounds.none { it.tag == "block" }) {
            newOutbounds.add(Outbound(type = "block", tag = "block"))
        }
        if (newOutbounds.none { it.tag == "dns-out" }) {
            newOutbounds.add(Outbound(type = "dns", tag = "dns-out"))
        }
        
        val newConfig = SingBoxConfig(outbounds = newOutbounds)
        
        // 4. 保存配置文件
        val configFile = File(configDir, "$profileId.json")
        configFile.writeText(gson.toJson(newConfig))
        
        // 5. 更新内存状态
        cacheConfig(profileId, newConfig)
        val nodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = nodes
        
        // 6. 如果是新配置，添加到 profiles 列表
        if (manualProfile == null) {
            manualProfile = ProfileUi(
                id = profileId,
                name = manualProfileName,
                type = ProfileType.Imported,
                url = null,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                updateStatus = UpdateStatus.Idle
            )
            _profiles.update { it + manualProfile }
        } else {
            // 更新 lastUpdated
            _profiles.update { list ->
                list.map { if (it.id == profileId) it.copy(lastUpdated = System.currentTimeMillis()) else it }
            }
        }
        
        // 7. 更新全局节点状态
        updateAllNodesAndGroups()
        
        // 8. 激活配置并选中新节点
        setActiveProfile(profileId)
        val addedNode = nodes.find { it.name == finalTag }
        if (addedNode != null) {
            _activeNodeId.value = addedNode.id
        }
        
        // 9. 保存配置
        saveProfiles()
        
        Result.success(addedNode ?: nodes.last())
    } catch (e: Exception) {
        Log.e(TAG, "Failed to add single node", e)
        Result.failure(Exception("添加节点失败: ${e.message}"))
    }
}
```

### 2. 修改 NodesViewModel.addNode() 方法

修改 `NodesViewModel.kt` 中的 `addNode()` 方法：

```kotlin
// 添加新的 StateFlow 用于反馈
private val _addNodeResult = MutableStateFlow<String?>(null)
val addNodeResult: StateFlow<String?> = _addNodeResult.asStateFlow()

fun clearAddNodeResult() {
    _addNodeResult.value = null
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
```

### 3. 修改 NodesScreen.kt 添加反馈显示

在 `NodesScreen.kt` 中添加对添加结果的监听和显示：

```kotlin
// 在现有的 collectAsState 声明区域添加
val addNodeResult by viewModel.addNodeResult.collectAsState()

// 在现有的 LaunchedEffect 区域添加
LaunchedEffect(addNodeResult) {
    addNodeResult?.let { message ->
        snackbarHostState.showSnackbar(message)
        viewModel.clearAddNodeResult()
    }
}
```

### 4. 修复 ClashYamlParser.canParse() 误匹配问题（可选优化）

修改 `ClashYamlParser.kt` 的 `canParse()` 方法，增加更严格的检查：

```kotlin
override fun canParse(content: String): Boolean {
    val trimmed = content.trim()
    
    // 排除明显是节点链接的情况
    val nodeLinkPrefixes = listOf(
        "vmess://", "vless://", "ss://", "trojan://",
        "hysteria2://", "hy2://", "hysteria://",
        "tuic://", "anytls://", "wireguard://", "ssh://"
    )
    if (nodeLinkPrefixes.any { trimmed.startsWith(it) }) {
        return false
    }
    
    // 检查是否包含 YAML 特征
    return trimmed.contains("proxies:") || trimmed.contains("proxy-groups:")
}
```

## 文件修改清单

| 文件路径 | 修改类型 | 说明 |
|---------|---------|-----|
| `app/src/main/java/com/kunk/singbox/repository/ConfigRepository.kt` | 添加方法 | 添加 `addSingleNode()` 方法 |
| `app/src/main/java/com/kunk/singbox/viewmodel/NodesViewModel.kt` | 修改方法 | 修改 `addNode()` 方法，添加 `addNodeResult` StateFlow |
| `app/src/main/java/com/kunk/singbox/ui/screens/NodesScreen.kt` | 添加监听 | 添加对 `addNodeResult` 的监听和 Snackbar 显示 |
| `app/src/main/java/com/kunk/singbox/utils/parser/ClashYamlParser.kt` | 优化 | 改进 `canParse()` 方法避免误匹配（可选） |

## 测试验证

1. **场景1：首次添加节点（无任何配置）**
   - 预期：节点成功添加，显示在列表中，显示成功提示

2. **场景2：已有"手动添加"配置时添加节点**
   - 预期：节点添加到已有配置中，不创建新配置，显示成功提示

3. **场景3：添加已存在同名节点**
   - 预期：自动添加后缀，成功添加，显示成功提示

4. **场景4：添加无效链接**
   - 预期：显示错误提示"无法解析节点链接"或"不支持的链接格式"

5. **场景5：各种协议类型的节点**
   - 测试 vmess, vless, ss, trojan, hysteria2, tuic 等链接的添加

## 预期效果

修复后，用户可以：
1. 在没有任何配置的情况下直接添加节点
2. 添加的节点会立即显示在列表中
3. 多次添加会合并到同一个"手动添加"配置中
4. 每次添加都会收到明确的成功或失败反馈