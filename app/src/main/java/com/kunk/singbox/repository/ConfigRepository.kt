package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Intent
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.model.*
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import com.kunk.singbox.repository.TrafficRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import com.kunk.singbox.utils.NetworkClient
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * 配置仓库 - 负责获取、解析和存储配置
 */
class ConfigRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "ConfigRepository"
        
        // User-Agent 列表，按优先级排序
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", // Browser - 优先尝试获取通用 Base64 订阅，以绕过服务端的客户端过滤
            "ClashMeta/1.18.0",             // ClashMeta - 次选
            "sing-box/1.10.0",              // Sing-box
            "Clash.Meta/1.18.0",
            "Clash/1.18.0",
            "SFA/1.10.0"
        )
        
        @Volatile
        private var instance: ConfigRepository? = null
        
        fun getInstance(context: Context): ConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val singBoxCore = SingBoxCore.getInstance(context)
    private val settingsRepository = SettingsRepository.getInstance(context)

    /**
     * 获取实际使用的 TUN 栈模式
     * 针对特定不支持 System 模式的设备强制使用 gVisor
     * 否则返回用户选择的模式
     */
    private fun getEffectiveTunStack(userSelected: TunStack): TunStack {
        // 针对特定不支持 System 模式的设备强制使用 gVisor
        // 这些设备在 System 模式下会报错 "bind forwarder to interface: operation not permitted"
        val model = Build.MODEL
        if (model.contains("SM-G986U", ignoreCase = true)) {
            Log.w(TAG, "Device $model detected, forcing GVISOR stack (ignoring user selection: ${userSelected.name})")
            return TunStack.GVISOR
        }

        return userSelected
    }

    // client 改为动态获取，以支持可配置的超时
    // 使用不带重试的 Client，避免订阅获取时超时时间被重试机制延长
    private fun getClient(): okhttp3.OkHttpClient {
        val timeout = runBlocking { settingsRepository.settings.first().subscriptionUpdateTimeout.toLong() }
        return NetworkClient.createClientWithoutRetry(timeout, timeout, timeout)
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val subscriptionManager = SubscriptionManager(listOf(
        SingBoxParser(gson),
        com.kunk.singbox.utils.parser.ClashYamlParser(),
        Base64Parser { NodeLinkParser(gson).parse(it) }
    ))

    private val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())
    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()
    
    private val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    private val _allNodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val allNodes: StateFlow<List<NodeUi>> = _allNodes.asStateFlow()
    
    private val _nodeGroups = MutableStateFlow<List<String>>(listOf("全部"))
    val nodeGroups: StateFlow<List<String>> = _nodeGroups.asStateFlow()

    private val _allNodeGroups = MutableStateFlow<List<String>>(emptyList())
    val allNodeGroups: StateFlow<List<String>> = _allNodeGroups.asStateFlow()
    
    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()
    
    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()
    
    private val maxConfigCacheSize = 2
    private val configCache = ConcurrentHashMap<String, SingBoxConfig>()
    private val configCacheOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val profileNodes = ConcurrentHashMap<String, List<NodeUi>>()
    private val profileResetJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val inFlightLatencyTests = ConcurrentHashMap<String, Deferred<Long>>()

    private val allNodesUiActiveCount = AtomicInteger(0)
    @Volatile private var allNodesLoadedForUi: Boolean = false
    
    @Volatile private var lastTagToNodeName: Map<String, String> = emptyMap()
    // 缓存上一次运行的配置中的 Outbound Tags 集合，用于判断是否需要重启 VPN
    @Volatile private var lastRunOutboundTags: Set<String>? = null
    // 缓存上一次运行的配置 ID，用于判断是否跨配置切换
    @Volatile private var lastRunProfileId: String? = null

    fun resolveNodeNameFromOutboundTag(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        if (tag.equals("PROXY", ignoreCase = true)) return null
        return when (tag) {
            "direct" -> context.getString(R.string.outbound_tag_direct)
            "block" -> context.getString(R.string.outbound_tag_block)
            "dns-out" -> "DNS"
            else -> {
                lastTagToNodeName[tag]
                    ?: _allNodes.value.firstOrNull { it.name == tag }?.name
            }
        }
    }
    
    private val configDir: File
        get() = File(context.filesDir, "configs").also { it.mkdirs() }
    
    private val profilesFile: File
        get() = File(context.filesDir, "profiles.json")
    
    init {
        loadSavedProfiles()
    }
    
    private fun loadConfig(profileId: String): SingBoxConfig? {
        configCache[profileId]?.let { return it }

        val configFile = File(configDir, "$profileId.json")
        if (!configFile.exists()) return null

        return try {
            val configJson = configFile.readText()
            var config = gson.fromJson(configJson, SingBoxConfig::class.java)
            config = deduplicateTags(config)
            cacheConfig(profileId, config)
            config
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config for profile: $profileId", e)
            null
        }
    }

    private fun cacheConfig(profileId: String, config: SingBoxConfig) {
        configCache[profileId] = config
        configCacheOrder.remove(profileId)
        configCacheOrder.addLast(profileId)
        while (configCache.size > maxConfigCacheSize && configCacheOrder.isNotEmpty()) {
            val oldest = configCacheOrder.pollFirst()
            if (oldest != null && oldest != profileId) {
                configCache.remove(oldest)
            }
        }
    }

    private fun removeCachedConfig(profileId: String) {
        configCache.remove(profileId)
        configCacheOrder.remove(profileId)
    }

    private fun saveProfiles() {
        try {
            val data = SavedProfilesData(
                profiles = _profiles.value,
                activeProfileId = _activeProfileId.value,
                activeNodeId = _activeNodeId.value
            )

            val json = gson.toJson(data)

            // Robust atomic write implementation
            val tmpFile = File(profilesFile.parent, "${profilesFile.name}.tmp")
            try {
                tmpFile.writeText(json)
                if (tmpFile.exists() && tmpFile.length() > 0) {
                    if (profilesFile.exists()) {
                        profilesFile.delete()
                    }
                    if (!tmpFile.renameTo(profilesFile)) {
                        Log.e(TAG, "Rename failed, falling back to copy")
                        tmpFile.copyTo(profilesFile, overwrite = true)
                        tmpFile.delete()
                    }
                } else {
                    Log.e(TAG, "Tmp file is empty, skipping save to prevent data corruption")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Internal error during save write", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profiles", e)
        }
    }
    
    private fun updateAllNodesAndGroups() {
        if (allNodesUiActiveCount.get() <= 0) {
            _allNodes.value = emptyList()
            _allNodeGroups.value = emptyList()
            return
        }

        val all = profileNodes.values.flatten()
        _allNodes.value = all

        val groups = all.map { it.group }.distinct().sorted()
        _allNodeGroups.value = groups
    }

    private fun loadAllNodesSnapshot(): List<NodeUi> {
        val result = ArrayList<NodeUi>()
        val profiles = _profiles.value
        for (p in profiles) {
            val cfg = loadConfig(p.id) ?: continue
            result.addAll(extractNodesFromConfig(cfg, p.id))
        }
        return result
    }

    fun setAllNodesUiActive(active: Boolean) {
        if (active) {
            val after = allNodesUiActiveCount.incrementAndGet()
            if (after == 1 && !allNodesLoadedForUi) {
                scope.launch {
                    val profiles = _profiles.value
                    for (p in profiles) {
                        val cfg = loadConfig(p.id) ?: continue
                        profileNodes[p.id] = extractNodesFromConfig(cfg, p.id)
                    }
                    updateAllNodesAndGroups()
                    allNodesLoadedForUi = true
                }
            }
        } else {
            while (true) {
                val cur = allNodesUiActiveCount.get()
                if (cur <= 0) break
                if (allNodesUiActiveCount.compareAndSet(cur, cur - 1)) break
            }
            if (allNodesUiActiveCount.get() <= 0) {
                allNodesLoadedForUi = false
                val activeId = _activeProfileId.value
                val keep = activeId?.let { profileNodes[it] }
                profileNodes.clear()
                if (activeId != null && keep != null) {
                    profileNodes[activeId] = keep
                }
                _allNodes.value = emptyList()
                _allNodeGroups.value = emptyList()
            }
        }
    }

    private fun updateLatencyInAllNodes(nodeId: String, latency: Long) {
        _allNodes.update { list ->
            list.map {
                if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else -1L) else it
            }
        }
    }

    /**
     * 重新加载所有保存的配置
     * 用于导入数据后刷新内存状态
     */
    fun reloadProfiles() {
        loadSavedProfiles()
    }

    private fun loadSavedProfiles() {
        try {
            if (profilesFile.exists()) {
                val json = profilesFile.readText()
                val savedData = gson.fromJson(json, SavedProfilesData::class.java)
                
                // Gson 有时会将泛型列表中的对象反序列化为 LinkedTreeMap，而不是目标对象 (ProfileUi)
                // 这通常发生在类型擦除或混淆导致类型信息丢失的情况下
                // 强制转换或重新映射以确保类型正确
                val safeProfiles = savedData.profiles.map { profile ->
                    // 强制转换为 Any? 以绕过编译器的类型检查，
                    // 因为在运行时 profile 可能是 LinkedTreeMap (类型擦除导致)
                    // 即使声明类型是 ProfileUi
                    val obj = profile as Any?
                    if (obj is com.google.gson.internal.LinkedTreeMap<*, *>) {
                        val jsonStr = gson.toJson(obj)
                        gson.fromJson(jsonStr, ProfileUi::class.java)
                    } else {
                        profile
                    }
                }

                // 加载时重置所有配置的更新状态为 Idle，防止因异常退出导致一直显示更新中
                _profiles.value = safeProfiles.map {
                    it.copy(updateStatus = UpdateStatus.Idle)
                }
                _activeProfileId.value = savedData.activeProfileId
                
                // 加载活跃配置的节点
                savedData.profiles.forEach { profile ->
                    if (profile.id != savedData.activeProfileId) return@forEach
                    val configFile = File(configDir, "${profile.id}.json")
                    if (configFile.exists()) {
                        try {
                            val configJson = configFile.readText()
                            val config = gson.fromJson(configJson, SingBoxConfig::class.java)
                            val nodes = extractNodesFromConfig(config, profile.id)
                            profileNodes[profile.id] = nodes
                            cacheConfig(profile.id, config)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load config for profile: ${profile.id}", e)
                        }
                    }
                }
                if (allNodesUiActiveCount.get() > 0) {
                    updateAllNodesAndGroups()
                }
                
                _activeProfileId.value?.let { activeId ->
                    profileNodes[activeId]?.let { nodes ->
                        _nodes.value = nodes
                        updateNodeGroups(nodes)
                        val restored = savedData.activeNodeId
                        _activeNodeId.value = when {
                            !restored.isNullOrBlank() && nodes.any { it.id == restored } -> {
                                restored
                            }
                            nodes.isNotEmpty() -> {
                                nodes.first().id
                            }
                            else -> {
                                Log.w(TAG, "loadSavedProfiles: No nodes available, activeNodeId set to null")
                                null
                            }
                        }
                    } ?: run {
                        Log.w(TAG, "loadSavedProfiles: profileNodes[$activeId] is null, activeNodeId not restored")
                    }
                } ?: run {
                    Log.w(TAG, "loadSavedProfiles: activeProfileId is null, activeNodeId not restored")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved profiles", e)
        }
    }
    
    /**
     * 从订阅 URL 导入配置
     */
    data class SubscriptionUserInfo(
        val upload: Long = 0,
        val download: Long = 0,
        val total: Long = 0,
        val expire: Long = 0
    )

    private data class FetchResult(
        val config: SingBoxConfig,
        val userInfo: SubscriptionUserInfo?
    )

    /**
     * 解析流量字符串 (支持 B, KB, MB, GB, TB, PB)
     */
    private fun parseTrafficString(value: String): Long {
        val trimmed = value.trim().uppercase()
        val regex = Regex("([\\d.]+)\\s*([KMGTPE]?)B?")
        val match = regex.find(trimmed) ?: return 0L
        
        val (numStr, unit) = match.destructured
        val num = numStr.toDoubleOrNull() ?: return 0L
        
        val multiplier = when (unit) {
            "K" -> 1024L
            "M" -> 1024L * 1024
            "G" -> 1024L * 1024 * 1024
            "T" -> 1024L * 1024 * 1024 * 1024
            "P" -> 1024L * 1024 * 1024 * 1024 * 1024
            else -> 1L
        }
        
        return (num * multiplier).toLong()
    }

    /**
     * 解析日期字符串 (yyyy-MM-dd)
     */
    private fun parseDateString(value: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            (sdf.parse(value.trim())?.time ?: 0L) / 1000 // Convert to seconds
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 解析 Subscription-Userinfo 头或 Body 中的状态信息
     * 支持标准 Header 格式和常见的 Body 文本格式 (如 STATUS=...)
     */
    private fun parseSubscriptionUserInfo(header: String?, bodyDecoded: String? = null): SubscriptionUserInfo? {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        var found = false

        // 1. 尝试解析 Header
        if (!header.isNullOrBlank()) {
            try {
                header.split(";").forEach { part ->
                    val kv = part.trim().split("=")
                    if (kv.size == 2) {
                        val key = kv[0].trim().lowercase()
                        val value = kv[1].trim().toLongOrNull() ?: 0L
                        when (key) {
                            "upload" -> { upload = value; found = true }
                            "download" -> { download = value; found = true }
                            "total" -> { total = value; found = true }
                            "expire" -> { expire = value; found = true }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Subscription-Userinfo header: $header", e)
            }
        }

        // 2. 如果 Header 没有完整信息，尝试从 Body 解析
        // 格式示例: STATUS=🚀:0.12GB,🚀:37.95GB,TOT:100GB🗓Expires:2026-01-02
        if (bodyDecoded != null && (!found || total == 0L)) {
            try {
                val firstLine = bodyDecoded.lines().firstOrNull()?.trim()
                if (firstLine != null && (firstLine.startsWith("STATUS=") || firstLine.contains("TOT:") || firstLine.contains("Expires:"))) {
                    // 解析 TOT:
                    val totalMatch = Regex("TOT:([\\d.]+[KMGTPE]?)B?").find(firstLine)
                    if (totalMatch != null) {
                        total = parseTrafficString(totalMatch.groupValues[1])
                        found = true
                    }

                    // 解析 Expires:
                    val expireMatch = Regex("Expires:(\\d{4}-\\d{2}-\\d{2})").find(firstLine)
                    if (expireMatch != null) {
                        expire = parseDateString(expireMatch.groupValues[1])
                        found = true
                    }

                    // 解析已用流量 (Upload/Download)
                    // 假设除此之外的流量数据都是已用流量，或者匹配特定图标/格式
                    // 示例中的已用流量是两个 🚀: value，分别对应 up/down 或已用
                    // 我们简单地提取所有类似 X:ValueGB 的格式，除了 TOT
                    // 我们重新策略：
                    // 如果有 upload/download 关键字更好。如果没有，尝试解析所有数字。
                    // 针对 specific case: 🚀:0.12GB,🚀:37.95GB
                    // 匹配所有非 TOT 的流量
                    var usedAccumulator = 0L
                    val parts = firstLine.substringAfter("STATUS=").split(",")
                    parts.forEach { part ->
                        if (part.contains("TOT:")) return@forEach
                        if (part.contains("Expires:")) return@forEach
                        
                        // 提取流量值
                        val match = Regex("([\\d.]+[KMGTPE]?)B?").find(part)
                        if (match != null) {
                            usedAccumulator += parseTrafficString(match.groupValues[1])
                            found = true
                        }
                    }
                    
                    if (usedAccumulator > 0) {
                        // 我们不知道哪个是 up 哪个是 down，暂且全部算作 download，或者平分
                        download = usedAccumulator
                        upload = 0
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse info from body: ${bodyDecoded.take(100)}", e)
            }
        }

        if (!found) return null
        return SubscriptionUserInfo(upload, download, total, expire)
    }

    /**
     * 使用多种 User-Agent 尝试获取订阅内容
     * 如果解析失败，依次尝试其他 UA
     *
     * @param url 订阅链接
     * @param onProgress 进度回调
     * @return 解析成功的配置及用户信息，如果所有尝试都失败则返回 null
     */
    private fun fetchAndParseSubscription(
        url: String,
        onProgress: (String) -> Unit = {}
    ): FetchResult? {
        var lastError: Exception? = null
        
        for ((index, userAgent) in USER_AGENTS.withIndex()) {
            try {
                onProgress("尝试获取订阅 (${index + 1}/${USER_AGENTS.size})...")
                
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/yaml,text/yaml,text/plain,application/json,*/*")
                    .build()

                var parsedConfig: SingBoxConfig? = null
                var userInfo: SubscriptionUserInfo? = null

                getClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Request failed with UA '$userAgent': HTTP ${response.code}")
                        if (index == USER_AGENTS.lastIndex) {
                            throw Exception("HTTP ${response.code}: ${response.message}")
                        }
                        return@use
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) {
                        Log.w(TAG, "Empty response with UA '$userAgent'")
                        if (index == USER_AGENTS.lastIndex) {
                            throw Exception("服务器返回空内容")
                        }
                        return@use
                    }

                    // 尝试从 Header 或 Body 解析 UserInfo
                    // 先尝试解码 Body 以便检查内容
                    val decodedBody = tryDecodeBase64(responseBody) ?: responseBody
                    userInfo = parseSubscriptionUserInfo(response.header("Subscription-Userinfo"), decodedBody)

                    val contentType = response.header("Content-Type") ?: ""

                    onProgress("正在解析配置...")

                    val config = parseSubscriptionResponse(responseBody)
                    if (config != null && config.outbounds != null && config.outbounds.isNotEmpty()) {
                        parsedConfig = config
                    } else {
                        Log.w(TAG, "Failed to parse response with UA '$userAgent'")
                        if (index == USER_AGENTS.lastIndex) {
                            // 如果是最后一次尝试，且内容不为空但无法解析，则可能是格式问题
                            // 但也有可能是网络截断等问题，这里我们记录为解析失败
                            // 让外层决定是否抛出异常（外层通过返回值 null 判断）
                        }
                    }
                }

                if (parsedConfig != null) {
                    Log.i(TAG, "Successfully parsed subscription with UA '$userAgent', got ${parsedConfig!!.outbounds?.size ?: 0} outbounds")
                    return FetchResult(parsedConfig!!, userInfo)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error with UA '$userAgent': ${e.message}")
                lastError = e
                // 如果是最后一次尝试，重新抛出异常以便上层捕获详细信息
                if (index == USER_AGENTS.lastIndex) {
                    throw e
                }
            }
        }
        
        // 理论上不会执行到这里，因为最后一次尝试会抛出异常
        lastError?.let { Log.e(TAG, "All User-Agents failed", it) }
        return null
    }

    private fun sanitizeSubscriptionSnippet(body: String, maxLen: Int = 220): String {
        var s = body
            .replace("\r", "")
            .replace("\n", "\\n")
            .trim()
        if (s.length > maxLen) s = s.substring(0, maxLen)

        s = s.replace(Regex("(?i)uuid\\s*[:=]\\s*[^\\\\n]+"), "uuid:***")
        s = s.replace(Regex("(?i)password\\s*[:=]\\s*[^\\\\n]+"), "password:***")
        s = s.replace(Regex("(?i)token\\s*[:=]\\s*[^\\\\n]+"), "token:***")
        return s
    }

    private fun parseClashYamlConfig(content: String): SingBoxConfig? {
        return com.kunk.singbox.utils.parser.ClashYamlParser().parse(content)
    }
    
    /**
     * 从订阅 URL 导入配置
     */
    suspend fun importFromSubscription(
        name: String,
        url: String,
        autoUpdateInterval: Int = 0,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        try {
            onProgress("正在获取订阅...")
            
            // 使用智能 User-Agent 切换策略获取订阅
            val fetchResult = try {
                fetchAndParseSubscription(url, onProgress)
            } catch (e: Exception) {
                // 捕获 fetchAndParseSubscription 抛出的具体网络异常
                Log.e(TAG, "Subscription fetch failed", e)
                return@withContext Result.failure(e)
            }

            if (fetchResult == null) {
                return@withContext Result.failure(Exception(context.getString(R.string.profiles_import_failed)))
            }
            
            val config = fetchResult.config
            val userInfo = fetchResult.userInfo

            onProgress(context.getString(R.string.profiles_extracting_nodes, 0, 0))
            
            val profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(config)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, onProgress)
            
            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception("No valid nodes found")) // TODO: add to strings.xml
            }
            
            // 保存配置文件
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(deduplicatedConfig))
            
            // 创建配置
            val profile = ProfileUi(
                id = profileId,
                name = name,
                type = ProfileType.Subscription,
                url = url,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                autoUpdateInterval = autoUpdateInterval,
                updateStatus = UpdateStatus.Idle,
                expireDate = userInfo?.expire ?: 0,
                totalTraffic = userInfo?.total ?: 0,
                usedTraffic = (userInfo?.upload ?: 0) + (userInfo?.download ?: 0)
            )
            
            // 保存到内存
            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()
            
            // 更新状态
            _profiles.update { it + profile }
            saveProfiles()
            
            // 如果是第一个配置，自动激活
            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }
            
            // 调度自动更新任务
            if (autoUpdateInterval > 0) {
                com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(context, profileId, autoUpdateInterval)
            }
            
            onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))
            
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Subscription import failed", e)
            // 确保抛出的异常信息对用户友好
            val msg = when(e) {
                is java.net.SocketTimeoutException -> "Connection timeout, please check your network"
                is java.net.UnknownHostException -> "Failed to resolve domain, please check the link"
                is javax.net.ssl.SSLHandshakeException -> "SSL certificate validation failed"
                else -> e.message ?: context.getString(R.string.profiles_import_failed)
            }
            Result.failure(Exception(msg))
        }
    }

    suspend fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported,
        onProgress: (String) -> Unit = {}
    ): Result<ProfileUi> = withContext(Dispatchers.IO) {
        try {
            onProgress(context.getString(R.string.common_loading))

            val normalized = normalizeImportedContent(content)
            val config = subscriptionManager.parse(normalized)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.profiles_import_failed)))

            onProgress(context.getString(R.string.profiles_extracting_nodes, 0, 0))

            val profileId = UUID.randomUUID().toString()
            val deduplicatedConfig = deduplicateTags(config)
            val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, onProgress)

            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception("No valid nodes found")) // TODO: add to strings.xml
            }

            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(deduplicatedConfig))

            val profile = ProfileUi(
                id = profileId,
                name = name,
                type = profileType,
                url = null,
                lastUpdated = System.currentTimeMillis(),
                enabled = true,
                updateStatus = UpdateStatus.Idle
            )

            cacheConfig(profileId, deduplicatedConfig)
            profileNodes[profileId] = nodes
            updateAllNodesAndGroups()

            _profiles.update { it + profile }
            saveProfiles()

            if (_activeProfileId.value == null) {
                setActiveProfile(profileId)
            }

            onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))

            Result.success(profile)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun normalizeImportedContent(content: String): String {
        val trimmed = content.trim().trimStart('\uFEFF')
        val lines = trimmed.lines().toMutableList()

        fun isFenceLine(line: String): Boolean {
            val t = line.trim()
            if (t.startsWith("```")) return true
            return t.length >= 2 && t.all { it == '`' }
        }

        if (lines.isNotEmpty() && isFenceLine(lines.first())) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && isFenceLine(lines.last())) {
            lines.removeAt(lines.lastIndex)
        }

        return lines.joinToString("\n").trim()
    }

    private fun tryDecodeBase64(content: String): String? {
        val s = content.trim().trimStart('\uFEFF')
        if (s.isBlank()) return null
        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = Base64.decode(s, flags)
                val text = String(decoded)
                if (text.isNotBlank()) return text
            } catch (_: Exception) {}
        }
        return null
    }
    
    /**
     * 解析订阅响应
     */
    private fun parseSubscriptionResponse(content: String): SingBoxConfig? {
        val normalizedContent = normalizeImportedContent(content)

        // 1. 尝试直接解析为 sing-box JSON
        try {
            val config = gson.fromJson(normalizedContent, SingBoxConfig::class.java)
            // 兼容 outbounds 在 proxies 字段的情况
            val outbounds = config.outbounds ?: config.proxies
            if (outbounds != null && outbounds.isNotEmpty()) {
                // 如果是从 proxies 字段读取的，需要将其移动到 outbounds 字段
                return if (config.outbounds == null) config.copy(outbounds = outbounds) else config
            } else {
                Log.w(TAG, "Parsed as JSON but outbounds/proxies is empty/null. content snippet: ${sanitizeSubscriptionSnippet(normalizedContent)}")
            }
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "Failed to parse as JSON: ${e.message}")
            // 继续尝试其他格式
        }

        // 1.5 尝试解析 Clash YAML
        try {
            val yamlConfig = parseClashYamlConfig(normalizedContent)
            if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                return yamlConfig
            }
        } catch (_: Exception) {
        }
        
        // 2. 尝试 Base64 解码后解析
        try {
            val decoded = tryDecodeBase64(normalizedContent)
            if (decoded.isNullOrBlank()) {
                throw IllegalStateException("base64 decode failed")
            }
            
            // 尝试解析解码后的内容为 JSON
            try {
                val config = gson.fromJson(decoded, SingBoxConfig::class.java)
                val outbounds = config.outbounds ?: config.proxies
                if (outbounds != null && outbounds.isNotEmpty()) {
                    return if (config.outbounds == null) config.copy(outbounds = outbounds) else config
                } else {
                    Log.w(TAG, "Parsed decoded Base64 as JSON but outbounds is empty/null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse decoded Base64 as JSON: ${e.message}")
            }

            try {
                val yamlConfig = parseClashYamlConfig(decoded)
                if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                    return yamlConfig
                }
            } catch (_: Exception) {
            }

        } catch (e: Exception) {
            // 继续尝试其他格式
        }
        
        // 3. 尝试解析为节点链接列表 (每行一个链接)
        try {
            val lines = normalizedContent.trim().lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                // 尝试 Base64 解码整体
                val decoded = tryDecodeBase64(normalizedContent) ?: normalizedContent
                
                val decodedLines = decoded.trim().lines().filter { it.isNotBlank() }
                val outbounds = mutableListOf<Outbound>()
                
                for (line in decodedLines) {
                    val cleanedLine = line.trim()
                        .removePrefix("- ")
                        .removePrefix("• ")
                        .trim()
                        .trim('`', '"', '\'')
                    val outbound = parseNodeLink(cleanedLine)
                    if (outbound != null) {
                        outbounds.add(outbound)
                    }
                }
                
                if (outbounds.isNotEmpty()) {
                    // 创建一个包含这些节点的配置
                    return SingBoxConfig(
                        outbounds = outbounds + listOf(
                            Outbound(type = "direct", tag = "direct"),
                            Outbound(type = "block", tag = "block")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return null
    }
    
    /**
     * 解析单个节点链接
     */
    private fun parseNodeLink(link: String): Outbound? {
        return when {
            link.startsWith("ss://") -> parseShadowsocksLink(link)
            link.startsWith("vmess://") -> parseVMessLink(link)
            link.startsWith("vless://") -> parseVLessLink(link)
            link.startsWith("trojan://") -> parseTrojanLink(link)
            link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2Link(link)
            link.startsWith("hysteria://") -> parseHysteriaLink(link)
            link.startsWith("anytls://") -> parseAnyTLSLink(link)
            link.startsWith("tuic://") -> parseTuicLink(link)
            link.startsWith("wireguard://") -> parseWireGuardLink(link)
            link.startsWith("ssh://") -> parseSSHLink(link)
            else -> null
        }
    }
    
    private fun parseShadowsocksLink(link: String): Outbound? {
        try {
            // ss://BASE64(method:password)@server:port#name
            // 或 ss://BASE64(method:password@server:port)#name
            val uri = link.removePrefix("ss://")
            val nameIndex = uri.lastIndexOf('#')
            val name = if (nameIndex > 0) java.net.URLDecoder.decode(uri.substring(nameIndex + 1), "UTF-8") else "SS Node"
            val mainPart = if (nameIndex > 0) uri.substring(0, nameIndex) else uri
            
            val atIndex = mainPart.lastIndexOf('@')
            if (atIndex > 0) {
                // 新格式: BASE64(method:password)@server:port
                val userInfo = String(Base64.decode(mainPart.substring(0, atIndex), Base64.URL_SAFE or Base64.NO_PADDING))
                val serverPart = mainPart.substring(atIndex + 1)
                val colonIndex = serverPart.lastIndexOf(':')
                val server = serverPart.substring(0, colonIndex)
                val port = serverPart.substring(colonIndex + 1).toInt()
                val methodPassword = userInfo.split(":", limit = 2)
                
                return Outbound(
                    type = "shadowsocks",
                    tag = name,
                    server = server,
                    serverPort = port,
                    method = methodPassword[0],
                    password = methodPassword.getOrElse(1) { "" }
                )
            } else {
                // 旧格式: BASE64(method:password@server:port)
                val decoded = String(Base64.decode(mainPart, Base64.URL_SAFE or Base64.NO_PADDING))
                val regex = Regex("(.+):(.+)@(.+):(\\d+)")
                val match = regex.find(decoded)
                if (match != null) {
                    val (method, password, server, port) = match.destructured
                    return Outbound(
                        type = "shadowsocks",
                        tag = name,
                        server = server,
                        serverPort = port.toInt(),
                        method = method,
                        password = password
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    /**
     * 解析 WireGuard 链接
     * 格式: wireguard://private_key@server:port?public_key=...&preshared_key=...&address=...&mtu=...#name
     */
    private fun parseWireGuardLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "WireGuard Node", "UTF-8")
            val privateKey = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 51820
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val peerPublicKey = params["public_key"] ?: params["peer_public_key"] ?: ""
            val preSharedKey = params["preshared_key"] ?: params["pre_shared_key"]
            val address = params["address"] ?: params["ip"]
            val mtu = params["mtu"]?.toIntOrNull() ?: 1420
            val reserved = params["reserved"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            
            val localAddresses = address?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            
            val peer = WireGuardPeer(
                server = server,
                serverPort = port,
                publicKey = peerPublicKey,
                preSharedKey = preSharedKey,
                allowedIps = listOf("0.0.0.0/0", "::/0"), // 默认全路由
                reserved = reserved
            )
            
            return Outbound(
                type = "wireguard",
                tag = name,
                localAddress = localAddresses,
                privateKey = privateKey,
                peers = listOf(peer),
                mtu = mtu
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * 解析 SSH 链接
     * 格式: ssh://user:password@server:port?params#name
     */
    private fun parseSSHLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "SSH Node", "UTF-8")
            val userInfo = uri.userInfo ?: ""
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 22
            
            val colonIndex = userInfo.indexOf(':')
            val user = if (colonIndex > 0) userInfo.substring(0, colonIndex) else userInfo
            val password = if (colonIndex > 0) userInfo.substring(colonIndex + 1) else null
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val privateKey = params["private_key"]
            val privateKeyPassphrase = params["private_key_passphrase"]
            val hostKey = params["host_key"]?.split(",")
            val clientVersion = params["client_version"]
            
            return Outbound(
                type = "ssh",
                tag = name,
                server = server,
                serverPort = port,
                user = user,
                password = password,
                privateKey = privateKey,
                privateKeyPassphrase = privateKeyPassphrase,
                hostKey = hostKey,
                clientVersion = clientVersion
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ssh link", e)
        }
        return null
    }

    private fun parseVMessLink(link: String): Outbound? {
        try {
            val base64Part = link.removePrefix("vmess://")
            val decoded = String(Base64.decode(base64Part, Base64.DEFAULT))
            val json = gson.fromJson(decoded, VMessLinkConfig::class.java)
            
            // 如果是 WS 且开启了 TLS，但没有指定 ALPN，默认强制使用 http/1.1
            val alpn = json.alpn?.split(",")?.filter { it.isNotBlank() }
            val finalAlpn = if (json.tls == "tls" && json.net == "ws" && (alpn == null || alpn.isEmpty())) {
                listOf("http/1.1")
            } else {
                alpn
            }

            val tlsConfig = if (json.tls == "tls") {
                TlsConfig(
                    enabled = true,
                    serverName = json.sni ?: json.host ?: json.add,
                    alpn = finalAlpn,
                    utls = json.fp?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
            } else null
            
            val transport = when (json.net) {
                "ws" -> {
                    val host = json.host ?: json.sni ?: json.add
                    val userAgent = if (json.fp?.contains("chrome") == true) {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                    } else {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                    }
                    val headers = mutableMapOf<String, String>()
                    if (!host.isNullOrBlank()) {
                        headers["Host"] = host
                    }
                    headers["User-Agent"] = userAgent

                    TransportConfig(
                        type = "ws",
                        path = json.path ?: "/",
                        headers = headers,
                        maxEarlyData = 2048,
                        earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    )
                }
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = json.path ?: ""
                )
                "h2" -> TransportConfig(
                    type = "http",
                    host = json.host?.let { listOf(it) },
                    path = json.path
                )
                "tcp" -> null
                else -> null
            }
            
            // 注意：sing-box 不支持 alter_id，只支持 AEAD 加密的 VMess (alterId=0)
            val aid = json.aid?.toIntOrNull() ?: 0
            if (aid != 0) {
                Log.w(TAG, "VMess node '${json.ps}' has alterId=$aid, sing-box only supports alterId=0 (AEAD)")
            }
            
            return Outbound(
                type = "vmess",
                tag = json.ps ?: "VMess Node",
                server = json.add,
                serverPort = json.port?.toIntOrNull() ?: 443,
                uuid = json.id,
                // alterId 已从模型中移除，sing-box 不支持
                security = json.scy ?: "auto",
                tls = tlsConfig,
                transport = transport,
                packetEncoding = json.packetEncoding ?: "xudp"
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun parseVLessLink(link: String): Outbound? {
        try {
            // vless://uuid@server:port?params#name
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "VLESS Node", "UTF-8")
            val uuid = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val security = params["security"] ?: "none"
            val sni = params["sni"] ?: params["host"] ?: server
            val insecure = params["allowInsecure"] == "1" || params["insecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]
            val packetEncoding = params["packetEncoding"] ?: "xudp"
            val transportType = params["type"] ?: "tcp"
            val flow = params["flow"]?.takeIf { it.isNotBlank() }

            val finalAlpnList = if (security == "tls" && transportType == "ws" && (alpnList == null || alpnList.isEmpty())) {
                listOf("http/1.1")
            } else {
                alpnList
            }
            
            val tlsConfig = when (security) {
                "tls" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = finalAlpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
                "reality" -> TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = finalAlpnList,
                    reality = RealityConfig(
                        enabled = true,
                        publicKey = params["pbk"],
                        shortId = params["sid"]
                    ),
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
                else -> null
            }
            
            val transport = when (transportType) {
                "ws" -> {
                    val host = params["host"] ?: sni
                    val rawWsPath = params["path"] ?: "/"
                    
                    // 从路径中提取 ed 参数
                    val earlyDataSize = params["ed"]?.toIntOrNull()
                        ?: Regex("""(?:\?|&)ed=(\d+)""")
                            .find(rawWsPath)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    val maxEarlyData = earlyDataSize ?: 2048
                    
                    // 从路径中移除 ed 参数，只保留纯路径
                    val cleanPath = rawWsPath
                        .replace(Regex("""\?ed=\d+(&|$)"""), "")
                        .replace(Regex("""&ed=\d+"""), "")
                        .trimEnd('?', '&')
                        .ifEmpty { "/" }
                    
                    val userAgent = if (fingerprint?.contains("chrome") == true) {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                    } else {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                    }
                    val headers = mutableMapOf<String, String>()
                    if (!host.isNullOrBlank()) {
                        headers["Host"] = host
                    }
                    headers["User-Agent"] = userAgent

                    TransportConfig(
                        type = "ws",
                        path = cleanPath,
                        headers = headers,
                        maxEarlyData = maxEarlyData,
                        earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    )
                }
                "grpc" -> TransportConfig(
                    type = "grpc",
                    serviceName = params["serviceName"] ?: params["sn"] ?: ""
                )
                "http", "h2", "httpupgrade" -> TransportConfig(
                    type = "http",
                    path = params["path"],
                    host = params["host"]?.let { listOf(it) }
                )
                "tcp" -> null
                else -> null
            }
            
            return Outbound(
                type = "vless",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                flow = flow,
                tls = tlsConfig,
                transport = transport,
                packetEncoding = packetEncoding
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun parseTrojanLink(link: String): Outbound? {
        try {
            // trojan://password@server:port?params#name
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Trojan Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val sni = params["sni"] ?: server
            val insecure = params["allowInsecure"] == "1" || params["insecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]
            
            return Outbound(
                type = "trojan",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse trojan link", e)
        }
        return null
    }
    
    private fun parseHysteria2Link(link: String): Outbound? {
        try {
            // hysteria2://password@server:port?params#name
            val uri = java.net.URI(link.replace("hy2://", "hysteria2://"))
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria2 Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port == -1) 443 else uri.port
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            return Outbound(
                type = "hysteria2",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: server,
                    insecure = params["insecure"] == "1"
                ),
                obfs = params["obfs"]?.let { obfsType ->
                    ObfsConfig(
                        type = obfsType,
                        password = params["obfs-password"]
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse hysteria2 link", e)
        }
        return null
    }
    
    private fun parseHysteriaLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port == -1) 443 else uri.port
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            return Outbound(
                type = "hysteria",
                tag = name,
                server = server,
                serverPort = port,
                authStr = params["auth"],
                upMbps = params["upmbps"]?.toIntOrNull(),
                downMbps = params["downmbps"]?.toIntOrNull(),
                tls = TlsConfig(
                    enabled = true,
                    serverName = params["sni"] ?: server,
                    insecure = params["insecure"] == "1",
                    alpn = params["alpn"]?.split(",")
                ),
                obfs = params["obfs"]?.let { ObfsConfig(type = it) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse hysteria link", e)
        }
        return null
    }
    
    /**
     * 解析 AnyTLS 链接
     * 格式: anytls://password@server:port?params#name
     */
    private fun parseAnyTLSLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "AnyTLS Node", "UTF-8")
            val password = uri.userInfo
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    try {
                        params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                    } catch (e: Exception) {
                        params[parts[0]] = parts[1]
                    }
                }
            }
            
            val sni = params["sni"] ?: server
            val insecure = params["insecure"] == "1" || params["allowInsecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }
            
            return Outbound(
                type = "anytls",
                tag = name,
                server = server,
                serverPort = port,
                password = password,
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                ),
                idleSessionCheckInterval = params["idle_session_check_interval"],
                idleSessionTimeout = params["idle_session_timeout"],
                minIdleSession = params["min_idle_session"]?.toIntOrNull()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    /**
     * 解析 TUIC 链接
     * 格式: tuic://uuid:password@server:port?params#name
     */
    private fun parseTuicLink(link: String): Outbound? {
        try {
            val uri = java.net.URI(link)
            val name = java.net.URLDecoder.decode(uri.fragment ?: "TUIC Node", "UTF-8")
            val server = uri.host
            val port = if (uri.port > 0) uri.port else 443
            
            // 解析 userInfo: uuid:password
            val userInfo = uri.userInfo ?: ""
            val colonIndex = userInfo.indexOf(':')
            val uuid = if (colonIndex > 0) userInfo.substring(0, colonIndex) else userInfo
            var password = if (colonIndex > 0) userInfo.substring(colonIndex + 1) else ""
            
            val params = mutableMapOf<String, String>()
            uri.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    try {
                        params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                    } catch (e: Exception) {
                        params[parts[0]] = parts[1]
                    }
                }
            }
            
            // 如果 password 为空，尝试从 query 参数中获取，或使用 UUID 作为密码
            if (password.isBlank()) {
                password = params["password"] ?: params["token"] ?: uuid
            }
            
            val sni = params["sni"] ?: server
            val insecure = params["allow_insecure"] == "1" || params["allowInsecure"] == "1" || params["insecure"] == "1"
            val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }
            
            return Outbound(
                type = "tuic",
                tag = name,
                server = server,
                serverPort = port,
                uuid = uuid,
                password = password,
                congestionControl = params["congestion_control"] ?: params["congestion"] ?: "bbr",
                udpRelayMode = params["udp_relay_mode"] ?: "native",
                zeroRttHandshake = params["reduce_rtt"] == "1" || params["zero_rtt"] == "1",
                tls = TlsConfig(
                    enabled = true,
                    serverName = sni,
                    insecure = insecure,
                    alpn = alpnList,
                    utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    /**
     * 从配置中提取节点
     */
    private fun extractNodesFromConfig(
        config: SingBoxConfig,
        profileId: String,
        onProgress: ((String) -> Unit)? = null
    ): List<NodeUi> {
        val nodes = mutableListOf<NodeUi>()
        val outbounds = config.outbounds ?: return nodes
        val trafficRepo = TrafficRepository.getInstance(context)

        fun stableNodeId(profileId: String, outboundTag: String): String {
            val key = "$profileId|$outboundTag"
            return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        }
        
        // 收集所有 selector 和 urltest 的 outbounds 作为分组
        val groupOutbounds = outbounds.filter { 
            it.type == "selector" || it.type == "urltest" 
        }
        
        // 创建节点到分组的映射
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }
        
        // 过滤出代理节点
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls", "http", "socks"
        )

        var processedCount = 0
        val totalCount = outbounds.size
        
        for (outbound in outbounds) {
            processedCount++
            // 每处理50个节点回调一次进度
            if (processedCount % 50 == 0) {
                onProgress?.invoke(context.getString(R.string.profiles_extracting_nodes, processedCount, totalCount))
            }

            if (outbound.type in proxyTypes) {
                var group = nodeToGroup[outbound.tag] ?: "Default"
                
                // 校验分组名是否为有效名称 (避免链接被当作分组名)
                if (group.contains("://") || group.length > 50) {
                    group = "未分组"
                }

                var regionFlag = detectRegionFlag(outbound.tag)
                
                // 如果从名称无法识别地区，尝试更深层次的信息挖掘
                if (regionFlag == "🌐" || regionFlag.isBlank()) {
                    // 1. 尝试 SNI (通常 CDN 节点会使用 SNI 指向真实域名)
                    val sni = outbound.tls?.serverName
                    if (!sni.isNullOrBlank()) {
                        val sniRegion = detectRegionFlag(sni)
                        if (sniRegion != "🌐" && sniRegion.isNotBlank()) regionFlag = sniRegion
                    }
                    
                    // 2. 尝试 Host (WS/HTTP Host)
                    if ((regionFlag == "🌐" || regionFlag.isBlank())) {
                        val host = outbound.transport?.headers?.get("Host")
                            ?: outbound.transport?.host?.firstOrNull()
                        if (!host.isNullOrBlank()) {
                            val hostRegion = detectRegionFlag(host)
                            if (hostRegion != "🌐" && hostRegion.isNotBlank()) regionFlag = hostRegion
                        }
                    }

                    // 3. 最后尝试服务器地址 (可能是 CDN IP，准确度较低，作为兜底)
                    if ((regionFlag == "🌐" || regionFlag.isBlank()) && !outbound.server.isNullOrBlank()) {
                        val serverRegion = detectRegionFlag(outbound.server)
                        if (serverRegion != "🌐" && serverRegion.isNotBlank()) regionFlag = serverRegion
                    }
                }
                
                // 2025-fix: 始终设置 regionFlag 以确保排序功能正常工作
                // UI 层 (NodeCard) 将负责检查名称中是否已包含该国旗，从而避免重复显示
                val finalRegionFlag = regionFlag

                // 2025 规范：确保 tag 已经应用了协议后缀（在 SubscriptionManager 中处理过了）
                // 这里我们只需确保 NodeUi 能够正确显示国旗
                
                val id = stableNodeId(profileId, outbound.tag)
                nodes.add(
                    NodeUi(
                        id = id,
                        name = outbound.tag,
                        protocol = outbound.type,
                        group = group,
                        regionFlag = finalRegionFlag,
                        latencyMs = null,
                        isFavorite = false,
                        sourceProfileId = profileId,
                        trafficUsed = trafficRepo.getMonthlyTotal(id),
                        tags = buildList {
                            outbound.tls?.let {
                                if (it.enabled == true) add("TLS")
                                it.reality?.let { r -> if (r.enabled == true) add("Reality") }
                            }
                            outbound.transport?.type?.let { add(it.uppercase()) }
                        }
                    )
                )
            }
        }
        
        return nodes
    }
    
    /**
     * 检测字符串是否包含国旗 Emoji
     */
    private fun containsFlagEmoji(str: String): Boolean {
        // 匹配区域指示符符号 (Regional Indicator Symbols) U+1F1E6..U+1F1FF
        // 两个区域指示符符号组成一个国旗
        // Java/Kotlin 中，这些字符是代理对 (Surrogate Pairs)
        // U+1F1E6 是 \uD83C\uDDE6
        // U+1F1FF 是 \uD83C\uDDFF
        // 正则表达式匹配两个连续的区域指示符
        val regex = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")
        
        // 另外，有些国旗 Emoji 可能不在这个范围内，或者已经被渲染为 Emoji
        // 我们也可以尝试匹配常见的国旗 Emoji 字符范围
        // 或者简单地，如果字符串包含任何 Emoji，我们可能都需要谨慎
        // 但目前先专注于国旗
        
        return regex.containsMatchIn(str)
    }

    /**
     * 根据节点名称检测地区标志

     * 使用词边界匹配，避免 "us" 匹配 "music" 等误报
     */
    private fun detectRegionFlag(name: String): String {
        val lowerName = name.lowercase()
        
        fun matchWord(vararg words: String): Boolean {
            return words.any { word ->
                val regex = Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)")
                regex.containsMatchIn(lowerName)
            }
        }
        
        return when {
            lowerName.contains("香港") || matchWord("hk") || lowerName.contains("hong kong") -> "🇭🇰"
            lowerName.contains("台湾") || matchWord("tw") || lowerName.contains("taiwan") -> "🇹🇼"
            lowerName.contains("日本") || matchWord("jp") || lowerName.contains("japan") || lowerName.contains("tokyo") -> "🇯🇵"
            lowerName.contains("新加坡") || matchWord("sg") || lowerName.contains("singapore") -> "🇸🇬"
            lowerName.contains("美国") || matchWord("us", "usa") || lowerName.contains("united states") || lowerName.contains("america") -> "🇺🇸"
            lowerName.contains("韩国") || matchWord("kr") || lowerName.contains("korea") -> "🇰🇷"
            lowerName.contains("英国") || matchWord("uk", "gb") || lowerName.contains("britain") || lowerName.contains("england") -> "🇬🇧"
            lowerName.contains("德国") || matchWord("de") || lowerName.contains("germany") -> "🇩🇪"
            lowerName.contains("法国") || matchWord("fr") || lowerName.contains("france") -> "🇫🇷"
            lowerName.contains("加拿大") || matchWord("ca") || lowerName.contains("canada") -> "🇨🇦"
            lowerName.contains("澳大利亚") || matchWord("au") || lowerName.contains("australia") -> "🇦🇺"
            lowerName.contains("俄罗斯") || matchWord("ru") || lowerName.contains("russia") -> "🇷🇺"
            lowerName.contains("印度") || matchWord("in") || lowerName.contains("india") -> "🇮🇳"
            lowerName.contains("巴西") || matchWord("br") || lowerName.contains("brazil") -> "🇧🇷"
            lowerName.contains("荷兰") || matchWord("nl") || lowerName.contains("netherlands") -> "🇳🇱"
            lowerName.contains("土耳其") || matchWord("tr") || lowerName.contains("turkey") -> "🇹🇷"
            lowerName.contains("阿根廷") || matchWord("ar") || lowerName.contains("argentina") -> "🇦🇷"
            lowerName.contains("马来西亚") || matchWord("my") || lowerName.contains("malaysia") -> "🇲🇾"
            lowerName.contains("泰国") || matchWord("th") || lowerName.contains("thailand") -> "🇹🇭"
            lowerName.contains("越南") || matchWord("vn") || lowerName.contains("vietnam") -> "🇻🇳"
            lowerName.contains("菲律宾") || matchWord("ph") || lowerName.contains("philippines") -> "🇵🇭"
            lowerName.contains("印尼") || matchWord("id") || lowerName.contains("indonesia") -> "🇮🇩"
            else -> "🌐"
        }
    }
    
    private fun updateNodeGroups(nodes: List<NodeUi>) {
        val groups = nodes.map { it.group }.distinct().sorted()
        _nodeGroups.value = listOf("全部") + groups
    }
    
    fun setActiveProfile(profileId: String, targetNodeId: String? = null) {
        _activeProfileId.value = profileId
        val cached = profileNodes[profileId]

        fun updateState(nodes: List<NodeUi>) {
            _nodes.value = nodes
            updateNodeGroups(nodes)

            val currentActiveId = _activeNodeId.value

            // 如果指定了目标节点且存在于列表中，直接选中
            if (targetNodeId != null && nodes.any { it.id == targetNodeId }) {
                _activeNodeId.value = targetNodeId
            }
            // 如果当前选中节点在新节点列表中，保持不变
            else if (currentActiveId != null && nodes.any { it.id == currentActiveId }) {
                // 不需要修改，已经是正确的值
            }
            // 如果当前没有选中节点，或选中的节点不在新列表中，选择第一个
            else if (nodes.isNotEmpty()) {
                val oldValue = _activeNodeId.value
                _activeNodeId.value = nodes.first().id
                if (oldValue != null) {
                    Log.w(TAG, "setActiveProfile.updateState: Current activeNodeId=$oldValue not in nodes list, resetting to first node: ${nodes.first().id}")
                } else {
                }
            }
        }

        if (cached != null) {
            updateState(cached)
        } else {
            _nodes.value = emptyList()
            _nodeGroups.value = listOf("全部")
            scope.launch {
                val cfg = loadConfig(profileId) ?: return@launch
                val nodes = extractNodesFromConfig(cfg, profileId)
                profileNodes[profileId] = nodes
                
                updateState(nodes)
                
                if (allNodesUiActiveCount.get() > 0) {
                    updateAllNodesAndGroups()
                }
            }
        }
        saveProfiles()
    }
    
    sealed class NodeSwitchResult {
        object Success : NodeSwitchResult()
        object NotRunning : NodeSwitchResult()
        data class Failed(val reason: String) : NodeSwitchResult()
    }

    suspend fun setActiveNode(nodeId: String): Boolean {
        val result = setActiveNodeWithResult(nodeId)
        return result is NodeSwitchResult.Success || result is NodeSwitchResult.NotRunning
    }

    suspend fun setActiveNodeWithResult(nodeId: String): NodeSwitchResult {
        val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()

        // Check for cross-profile switch
        val targetNode = allNodesSnapshot.find { it.id == nodeId }
        if (targetNode != null && targetNode.sourceProfileId != _activeProfileId.value) {
            Log.i(TAG, "Cross-profile switch detected: ${_activeProfileId.value} -> ${targetNode.sourceProfileId}")
            setActiveProfile(targetNode.sourceProfileId, nodeId)
            
        }

        _activeNodeId.value = nodeId
        saveProfiles()

        val persistedActive = VpnStateStore.getActive(context)
        val remoteRunning = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
        if (!persistedActive && !remoteRunning) {
            Log.i(TAG, "setActiveNodeWithResult: VPN not running (persistedActive=false, remoteRunning=false)")
            return NodeSwitchResult.NotRunning
        }
        if (!persistedActive && remoteRunning) {
            Log.w(TAG, "setActiveNodeWithResult: persistedActive=false but remoteRunning=true; proceeding with hot switch")
        }
        
        return withContext(Dispatchers.IO) {
            // 尝试从当前配置查找节点
            var node = _nodes.value.find { it.id == nodeId }
            
            // 如果找不到，尝试从所有节点查找（支持跨配置切换）
            if (node == null) {
                node = allNodesSnapshot.find { it.id == nodeId }
            }

            if (node == null) {
                val msg = "Target node not found: $nodeId"
                Log.w(TAG, msg)
                return@withContext NodeSwitchResult.Failed(msg)
            }
            
            try {
                val generationResult = generateConfigFile()
                if (generationResult == null) {
                    val msg = context.getString(R.string.dashboard_config_generation_failed)
                    Log.e(TAG, msg)
                    return@withContext NodeSwitchResult.Failed(msg)
                }

                // ... [Skipping comments for brevity in replacement]
                
                // 修正 cache.db 清理逻辑
                // 注意：这里删除可能不生效，因为 Service 进程关闭时可能会再次写入 cache.db
                // 因此我们在 Service 进程启动时增加了一个 EXTRA_CLEAN_CACHE 参数来确保删除
                runCatching {
                    // 兼容清理旧位置
                    val oldCacheDb = File(context.filesDir, "cache.db")
                    if (oldCacheDb.exists()) oldCacheDb.delete()
                }

                // 检查是否需要重启服务：如果 Outbound 列表发生了变化（例如跨配置切换、增删节点），
                // 或者当前配置 ID 发生了变化（跨配置切换），则必须重启 VPN 以加载新的配置文件。
                val currentTags = generationResult.outboundTags
                val profileChanged = lastRunProfileId != null && lastRunProfileId != _activeProfileId.value
                
                // 2025-fix: 改进 tagsChanged 判断逻辑
                // 问题：lastRunOutboundTags 在 App 启动后为 null，导致首次切换节点时
                // 即使配置没有实际变化，也会触发 VPN 完全重启
                // 修复：如果 VPN 已经在运行（remoteRunning=true），且 lastRunOutboundTags 为 null，
                // 则首先尝试热切换，不强制重启。只有当配置确实变化时才重启。
                val tagsActuallyChanged = lastRunOutboundTags != null && lastRunOutboundTags != currentTags
                val tagsChanged = tagsActuallyChanged || profileChanged
                
                // 更新缓存（在判断之后更新，确保下次能正确比较）
                lastRunOutboundTags = currentTags
                lastRunProfileId = _activeProfileId.value
                

                val coreMode = VpnStateStore.getMode(context)
                val intent = if (coreMode == VpnStateStore.CoreMode.PROXY) {
                    Intent(context, ProxyOnlyService::class.java).apply {
                        if (tagsChanged) {
                            action = ProxyOnlyService.ACTION_START
                            Log.i(TAG, "Outbound tags changed (or first run), forcing RESTART/RELOAD")
                        } else {
                            action = ProxyOnlyService.ACTION_SWITCH_NODE
                            Log.i(TAG, "Outbound tags match, attempting HOT SWITCH")
                        }
                        putExtra("node_id", nodeId)
                        putExtra("outbound_tag", generationResult.activeNodeTag)
                        putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, generationResult.path)
                    }
                } else {
                    Intent(context, SingBoxService::class.java).apply {
                        if (tagsChanged) {
                            action = SingBoxService.ACTION_START
                            putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
                            Log.i(TAG, "Outbound tags changed (or first run), forcing RESTART/RELOAD with CACHE CLEAN")
                        } else {
                            action = SingBoxService.ACTION_SWITCH_NODE
                            Log.i(TAG, "Outbound tags match, attempting HOT SWITCH")
                        }
                        putExtra("node_id", nodeId)
                        putExtra("outbound_tag", generationResult.activeNodeTag)
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, generationResult.path)
                    }
                }

                // Service already running (VPN active). Use startService to avoid foreground-service timing constraints.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tagsChanged) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                Log.i(TAG, "Requested switch for node: ${node.name} (Tag: ${generationResult.activeNodeTag}, Restart: $tagsChanged)")
                NodeSwitchResult.Success
            } catch (e: Exception) {

                val msg = "Switch error: ${e.message ?: "unknown error"}"
                Log.e(TAG, "Error during hot switch", e)
                NodeSwitchResult.Failed(msg)
            }
        }
    }

    suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean {
        if (proxyName.isNullOrBlank()) return false

        val activeProfileId = _activeProfileId.value
        val candidates = if (activeProfileId != null) {
            _nodes.value + _allNodes.value.filter { it.sourceProfileId != activeProfileId }
        } else {
            _allNodes.value
        }

        val matched = candidates.firstOrNull { it.name == proxyName } ?: return false
        if (_activeNodeId.value == matched.id) return true

        _activeNodeId.value = matched.id
        Log.i(TAG, "Synced active node from service selection: $proxyName -> ${matched.id}")
        return true
    }
    
    fun deleteProfile(profileId: String) {
        // 取消自动更新任务
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.cancel(context, profileId)
        
        _profiles.update { list -> list.filter { it.id != profileId } }
        removeCachedConfig(profileId)
        profileNodes.remove(profileId)
        updateAllNodesAndGroups()
        
        // 删除配置文件
        File(configDir, "$profileId.json").delete()
        
        if (_activeProfileId.value == profileId) {
            val newActiveId = _profiles.value.firstOrNull()?.id
            _activeProfileId.value = newActiveId
            if (newActiveId != null) {
                setActiveProfile(newActiveId)
            } else {
                _nodes.value = emptyList()
                _nodeGroups.value = listOf("全部")
                _activeNodeId.value = null
            }
        }
        saveProfiles()
    }
    
    fun toggleProfileEnabled(profileId: String) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(enabled = !it.enabled) else it
            }
        }
        saveProfiles()
    }

    fun updateProfileMetadata(profileId: String, newName: String, newUrl: String?, autoUpdateInterval: Int = 0) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) {
                    it.copy(name = newName, url = newUrl, autoUpdateInterval = autoUpdateInterval)
                } else {
                    it
                }
            }
        }
        saveProfiles()
        
        // 调度或取消自动更新任务
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(context, profileId, autoUpdateInterval)
    }

    /**
     * 测试单个节点的延迟（真正通过代理测试）
     * @param nodeId 节点 ID
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
    suspend fun testNodeLatency(nodeId: String): Long {
        val existing = inFlightLatencyTests[nodeId]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<Long>()
        val prev = inFlightLatencyTests.putIfAbsent(nodeId, deferred)
        if (prev != null) {
            return prev.await()
        }

        try {
            val result = withContext(Dispatchers.IO) {
                try {
                    val node = _nodes.value.find { it.id == nodeId }
                    if (node == null) {
                        Log.e(TAG, "Node not found: $nodeId")
                        return@withContext -1L
                    }

                    val config = loadConfig(node.sourceProfileId)
                    if (config == null) {
                        Log.e(TAG, "Config not found for profile: ${node.sourceProfileId}")
                        return@withContext -1L
                    }

                    val outbound = config.outbounds?.find { it.tag == node.name }
                    if (outbound == null) {
                        Log.e(TAG, "Outbound not found: ${node.name}")
                        return@withContext -1L
                    }

                    val fixedOutbound = buildOutboundForRuntime(outbound)
                    val latency = singBoxCore.testOutboundLatency(fixedOutbound)

                    _nodes.update { list ->
                        list.map {
                            if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else -1L) else it
                        }
                    }

                    profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                        if (it.id == nodeId) it.copy(latencyMs = if (latency > 0) latency else -1L) else it
                    } ?: emptyList()
                    updateLatencyInAllNodes(nodeId, latency)

                    latency
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        -1L
                    } else {
                        Log.e(TAG, "Latency test error for $nodeId", e)
                        // 2025-debug: 记录详细测速失败原因到日志系统，方便用户排查
                        val nodeName = _nodes.value.find { it.id == nodeId }?.name
                        com.kunk.singbox.repository.LogRepository.getInstance().addLog(context.getString(R.string.nodes_test_failed, nodeName ?: nodeId) + ": ${e.message}")
                        -1L
                    }
                }
            }
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            deferred.complete(-1L)
            return -1L
        } finally {
            inFlightLatencyTests.remove(nodeId, deferred)
        }
    }

    /**
     * 批量测试所有节点的延迟
     * 使用并发方式提高效率
     */
    suspend fun clearAllNodesLatency() = withContext(Dispatchers.IO) {
        _nodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }
        
        // Update profileNodes map
        profileNodes.keys.forEach { profileId ->
            profileNodes[profileId] = profileNodes[profileId]?.map {
                it.copy(latencyMs = null)
            } ?: emptyList()
        }
        _allNodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }
    }

    suspend fun testAllNodesLatency(targetNodeIds: List<String>? = null, onNodeComplete: ((String) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val allNodes = _nodes.value
        val nodes = if (targetNodeIds != null) {
            allNodes.filter { it.id in targetNodeIds }
        } else {
            allNodes
        }

        data class NodeTestInfo(
            val outbound: Outbound,
            val nodeId: String,
            val profileId: String
        )

        val testInfoList = nodes.mapNotNull { node ->
            val config = loadConfig(node.sourceProfileId) ?: return@mapNotNull null
            val outbound = config.outbounds?.find { it.tag == node.name } ?: return@mapNotNull null
            NodeTestInfo(buildOutboundForRuntime(outbound), node.id, node.sourceProfileId)
        }

        if (testInfoList.isEmpty()) {
            Log.w(TAG, "No valid nodes to test")
            return@withContext
        }

        val outbounds = testInfoList.map { it.outbound }
        val tagToInfo = testInfoList.associateBy { it.outbound.tag }

        singBoxCore.testOutboundsLatency(outbounds) { tag, latency ->
            val info = tagToInfo[tag] ?: return@testOutboundsLatency
            val latencyValue = if (latency > 0) latency else -1L
            
            _nodes.update { list ->
                list.map {
                    if (it.id == info.nodeId) it.copy(latencyMs = latencyValue) else it
                }
            }

            profileNodes[info.profileId] = profileNodes[info.profileId]?.map {
                if (it.id == info.nodeId) it.copy(latencyMs = latencyValue) else it
            } ?: emptyList()
            
            updateLatencyInAllNodes(info.nodeId, latency)

            onNodeComplete?.invoke(info.nodeId)
        }

    }

    suspend fun updateAllProfiles(): BatchUpdateResult {
        val enabledProfiles = _profiles.value.filter { it.enabled && it.type == ProfileType.Subscription }
        
        if (enabledProfiles.isEmpty()) {
            return BatchUpdateResult()
        }
        
        val results = mutableListOf<SubscriptionUpdateResult>()
        
        enabledProfiles.forEach { profile ->
            val result = updateProfile(profile.id)
            results.add(result)
        }
        
        return BatchUpdateResult(
            successWithChanges = results.count { it is SubscriptionUpdateResult.SuccessWithChanges },
            successNoChanges = results.count { it is SubscriptionUpdateResult.SuccessNoChanges },
            failed = results.count { it is SubscriptionUpdateResult.Failed },
            details = results
        )
    }
    
    suspend fun updateProfile(profileId: String): SubscriptionUpdateResult {
        val profile = _profiles.value.find { it.id == profileId }
            ?: return SubscriptionUpdateResult.Failed("未知配置", "配置不存在")
        
        if (profile.url.isNullOrBlank()) {
            return SubscriptionUpdateResult.Failed(profile.name, "无订阅链接")
        }
        
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Updating) else it
            }
        }
        
        val result = try {
            importFromSubscriptionUpdate(profile)
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "未知错误")
        }

        // 更新状态为 Success/Failed
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(
                    updateStatus = if (result is SubscriptionUpdateResult.Failed) UpdateStatus.Failed else UpdateStatus.Success,
                    lastUpdated = if (result is SubscriptionUpdateResult.Failed) it.lastUpdated else System.currentTimeMillis()
                ) else it
            }
        }

        // 异步延迟重置状态，不阻塞当前方法返回
        profileResetJobs.remove(profileId)?.cancel()
        val resetJob = scope.launch {
            kotlinx.coroutines.delay(2000)
            _profiles.update { list ->
                list.map {
                    if (it.id == profileId && it.updateStatus != UpdateStatus.Updating) {
                        it.copy(updateStatus = UpdateStatus.Idle)
                    } else {
                        it
                    }
                }
            }
        }
        resetJob.invokeOnCompletion {
            profileResetJobs.remove(profileId, resetJob)
        }
        profileResetJobs[profileId] = resetJob
        
        return result
    }
    
    private suspend fun importFromSubscriptionUpdate(profile: ProfileUi): SubscriptionUpdateResult = withContext(Dispatchers.IO) {
        try {
            // 获取旧的节点列表用于比较
            val oldNodes = profileNodes[profile.id] ?: emptyList()
            val oldNodeNames = oldNodes.map { it.name }.toSet()
            
            // 使用智能 User-Agent 切换策略获取订阅
            val fetchResult = fetchAndParseSubscription(profile.url!!) { /* 静默更新，不显示进度 */ }
                ?: return@withContext SubscriptionUpdateResult.Failed(profile.name, "无法解析配置")
            
            val config = fetchResult.config
            val userInfo = fetchResult.userInfo

            val deduplicatedConfig = deduplicateTags(config)
            val newNodes = extractNodesFromConfig(deduplicatedConfig, profile.id)
            val newNodeNames = newNodes.map { it.name }.toSet()
            
            // 计算变化
            val addedNodes = newNodeNames - oldNodeNames
            val removedNodes = oldNodeNames - newNodeNames
            
            // 更新存储
            val configFile = File(configDir, "${profile.id}.json")
            configFile.writeText(gson.toJson(deduplicatedConfig))
            
            cacheConfig(profile.id, deduplicatedConfig)
            profileNodes[profile.id] = newNodes
            updateAllNodesAndGroups()
            
            // 如果是当前活跃配置，更新节点列表
            if (_activeProfileId.value == profile.id) {
                _nodes.value = newNodes
                updateNodeGroups(newNodes)
            }
            
            // 更新用户信息
            _profiles.update { list ->
                list.map {
                    if (it.id == profile.id) {
                        it.copy(
                            expireDate = userInfo?.expire ?: it.expireDate,
                            totalTraffic = userInfo?.total ?: it.totalTraffic,
                            usedTraffic = if (userInfo != null) (userInfo.upload + userInfo.download) else it.usedTraffic
                        )
                    } else {
                        it
                    }
                }
            }

            saveProfiles()
            
            // 返回结果
            if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty()) {
                SubscriptionUpdateResult.SuccessWithChanges(
                    profileName = profile.name,
                    addedCount = addedNodes.size,
                    removedCount = removedNodes.size,
                    totalCount = newNodes.size
                )
            } else {
                SubscriptionUpdateResult.SuccessNoChanges(
                    profileName = profile.name,
                    totalCount = newNodes.size
                )
            }
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "未知错误")
        }
    }
    
    data class ConfigGenerationResult(
        val path: String,
        val activeNodeTag: String?,
        val outboundTags: Set<String>
    )

    /**
     * 生成用于 VPN 服务的配置文件
     * @return 配置文件路径和当前活跃节点的 Tag
     */
    suspend fun generateConfigFile(): ConfigGenerationResult? = withContext(Dispatchers.IO) {
        try {
            val activeId = _activeProfileId.value ?: return@withContext null
            val config = loadConfig(activeId) ?: return@withContext null
            val activeNodeId = _activeNodeId.value
            val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
            val activeNode = _nodes.value.find { it.id == activeNodeId }
                ?: allNodesSnapshot.find { it.id == activeNodeId }
            
            // 获取当前设置
            val settings = settingsRepository.settings.first()

            // 构建完整的运行配置
            val log = buildRunLogConfig()
            val experimental = buildRunExperimentalConfig(settings)
            val inbounds = buildRunInbounds(settings)
            
            // 先构建有效的规则集列表，供 DNS 和 Route 模块共用
            val customRuleSets = buildCustomRuleSets(settings)
            
            val dns = buildRunDns(settings, customRuleSets)

            val outboundsContext = buildRunOutbounds(config, activeNode, settings, allNodesSnapshot)
            val route = buildRunRoute(settings, outboundsContext.selectorTag, outboundsContext.outbounds, outboundsContext.nodeTagResolver, customRuleSets)

            lastTagToNodeName = outboundsContext.nodeTagMap.mapNotNull { (nodeId, tag) ->
                val name = allNodesSnapshot.firstOrNull { it.id == nodeId }?.name
                if (name.isNullOrBlank() || tag.isBlank()) null else (tag to name)
            }.toMap()

            val runConfig = config.copy(
                log = log,
                experimental = experimental,
                inbounds = inbounds,
                dns = dns,
                route = route,
                outbounds = outboundsContext.outbounds
            )

            val validation = singBoxCore.validateConfig(runConfig)
            validation.exceptionOrNull()?.let { e ->
                val msg = e.cause?.message ?: e.message ?: "unknown error"
                Log.e(TAG, "Config pre-validation failed: $msg", e)
                throw Exception("Config validation failed: $msg", e)
            }
            
            // 写入临时配置文件
            val configFile = File(context.filesDir, "running_config.json")
            configFile.writeText(gson.toJson(runConfig))
            
            
            // 解析当前选中的节点在运行配置中的实际 Tag
            val resolvedTag = activeNodeId?.let { outboundsContext.nodeTagMap[it] }
                ?: activeNode?.name
                
            // 收集所有 Outbound 的 tag
            val allTags = runConfig.outbounds?.map { it.tag }?.toSet() ?: emptySet()

            ConfigGenerationResult(configFile.absolutePath, resolvedTag, allTags)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate config file", e)
            null
        }
    }
    
    /**
     * 运行时修复 Outbound 配置
     * 包括：修复 interval 单位、清理 flow、补充 ALPN、补充 User-Agent、补充缺省值
     */
    private fun fixOutboundForRuntime(outbound: Outbound): Outbound {
        var result = outbound

        val interval = result.interval
        if (interval != null) {
            val fixedInterval = when {
                interval.matches(Regex("^\\d+$")) -> "${interval}s"
                interval.matches(Regex("^\\d+\\.\\d+$")) -> "${interval}s"
                interval.matches(Regex("^\\d+[smhd]$", RegexOption.IGNORE_CASE)) -> interval.lowercase()
                else -> interval
            }
            if (fixedInterval != interval) {
                result = result.copy(interval = fixedInterval)
            }
        }

        // Fix flow
        val cleanedFlow = result.flow?.takeIf { it.isNotBlank() }
        if (cleanedFlow != result.flow) {
            result = result.copy(flow = cleanedFlow)
        }

        // Fix URLTest - Convert to selector to avoid sing-box core panic during InterfaceUpdated
        // The urltest implementation in some sing-box versions has race condition issues
        if (result.type == "urltest" || result.type == "url-test") {
            var newOutbounds = result.outbounds
            if (newOutbounds.isNullOrEmpty()) {
                newOutbounds = listOf("direct")
            }
            
            // Convert urltest to selector to avoid crash
            result = result.copy(
                type = "selector",
                outbounds = newOutbounds,
                default = newOutbounds.firstOrNull(),
                interruptExistConnections = false,
                // Clear urltest-specific fields
                url = null,
                interval = null,
                tolerance = null
            )
        }
        
        // Fix Selector empty outbounds
        if (result.type == "selector" && result.outbounds.isNullOrEmpty()) {
            result = result.copy(outbounds = listOf("direct"))
        }

        fun isIpLiteral(value: String): Boolean {
            val v = value.trim()
            if (v.isEmpty()) return false
            val ipv4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
            if (ipv4.matches(v)) {
                return v.split(".").all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
            }
            val ipv6 = Regex("^[0-9a-fA-F:]+$")
            return v.contains(":") && ipv6.matches(v)
        }

        val tls = result.tls
        val transport = result.transport
        if (transport?.type == "ws" && tls?.enabled == true) {
            val wsHost = transport.headers?.get("Host")
                ?: transport.headers?.get("host")
                ?: transport.host?.firstOrNull()
            val sni = tls.serverName?.trim().orEmpty()
            val server = result.server?.trim().orEmpty()
            if (!wsHost.isNullOrBlank() && !isIpLiteral(wsHost)) {
                val needFix = sni.isBlank() || isIpLiteral(sni) || (server.isNotBlank() && sni.equals(server, ignoreCase = true))
                if (needFix && !wsHost.equals(sni, ignoreCase = true)) {
                    result = result.copy(tls = tls.copy(serverName = wsHost))
                }
            }
        }

        val tlsAfterSni = result.tls
        if (result.transport?.type == "ws" && tlsAfterSni?.enabled == true && (tlsAfterSni.alpn == null || tlsAfterSni.alpn.isEmpty())) {
            result = result.copy(tls = tlsAfterSni.copy(alpn = listOf("http/1.1")))
        }

        // Fix User-Agent and path for WS
        if (transport != null && transport.type == "ws") {
            val headers = transport.headers?.toMutableMap() ?: mutableMapOf()
            var needUpdate = false
            
            // 如果没有 Host，尝试从 SNI 或 Server 获取
            if (!headers.containsKey("Host")) {
                val host = transport.host?.firstOrNull()
                    ?: result.tls?.serverName
                    ?: result.server
                if (!host.isNullOrBlank()) {
                    headers["Host"] = host
                    needUpdate = true
                }
            }
            
            // 补充 User-Agent
            if (!headers.containsKey("User-Agent")) {
                val fingerprint = result.tls?.utls?.fingerprint
                val userAgent = if (fingerprint?.contains("chrome") == true) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                }
                headers["User-Agent"] = userAgent
                needUpdate = true
            }
            
            // 清理路径中的 ed 参数
            val rawPath = transport.path ?: "/"
            val cleanPath = rawPath
                .replace(Regex("""\?ed=\d+(&|$)"""), "")
                .replace(Regex("""&ed=\d+"""), "")
                .trimEnd('?', '&')
                .ifEmpty { "/" }
            
            val pathChanged = cleanPath != rawPath
            
            if (needUpdate || pathChanged) {
                result = result.copy(transport = transport.copy(
                    headers = headers,
                    path = cleanPath
                ))
            }
        }

        // 强制清理 VLESS 协议中的 security 字段 (sing-box 不支持)
        if (result.type == "vless" && result.security != null) {
            result = result.copy(security = null)
        }

        // Hysteria/Hysteria2: some sing-box/libbox builds require up_mbps/down_mbps to be present.
        // If missing, the core may fail to establish connections and the local proxy test will see Connection reset.
        if (result.type == "hysteria" || result.type == "hysteria2") {
            val up = result.upMbps
            val down = result.downMbps
            val defaultMbps = 50
            if (up == null || down == null) {
                result = result.copy(
                    upMbps = up ?: defaultMbps,
                    downMbps = down ?: defaultMbps
                )
            }
        }

        // 补齐 VMess packetEncoding 缺省值
        if (result.type == "vmess" && result.packetEncoding.isNullOrBlank()) {
            result = result.copy(packetEncoding = "xudp")
        }

        return result
    }

    private fun buildOutboundForRuntime(outbound: Outbound): Outbound {
        val fixed = fixOutboundForRuntime(outbound)
        return when (fixed.type) {
            "selector", "urltest", "url-test" -> Outbound(
                type = "selector",
                tag = fixed.tag,
                outbounds = fixed.outbounds,
                default = fixed.default,
                interruptExistConnections = fixed.interruptExistConnections
            )

            "direct", "block", "dns" -> Outbound(type = fixed.type, tag = fixed.tag)

            "vmess" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                security = fixed.security,
                packetEncoding = fixed.packetEncoding,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex
            )

            "vless" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                flow = fixed.flow,
                packetEncoding = fixed.packetEncoding,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex
            )

            "trojan" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex
            )

            "shadowsocks" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                method = fixed.method,
                password = fixed.password,
                plugin = fixed.plugin,
                pluginOpts = fixed.pluginOpts,
                udpOverTcp = fixed.udpOverTcp,
                multiplex = fixed.multiplex
            )

            "hysteria", "hysteria2" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                authStr = fixed.authStr,
                upMbps = fixed.upMbps,
                downMbps = fixed.downMbps,
                obfs = fixed.obfs,
                recvWindowConn = fixed.recvWindowConn,
                recvWindow = fixed.recvWindow,
                disableMtuDiscovery = fixed.disableMtuDiscovery,
                hopInterval = fixed.hopInterval,
                ports = fixed.ports,
                tls = fixed.tls,
                multiplex = fixed.multiplex
            )

            "tuic" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                password = fixed.password,
                congestionControl = fixed.congestionControl,
                udpRelayMode = fixed.udpRelayMode,
                zeroRttHandshake = fixed.zeroRttHandshake,
                heartbeat = fixed.heartbeat,
                disableSni = fixed.disableSni,
                mtu = fixed.mtu,
                tls = fixed.tls,
                multiplex = fixed.multiplex
            )

            "anytls" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                idleSessionCheckInterval = fixed.idleSessionCheckInterval,
                idleSessionTimeout = fixed.idleSessionTimeout,
                minIdleSession = fixed.minIdleSession,
                tls = fixed.tls,
                multiplex = fixed.multiplex
            )

            "wireguard" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                localAddress = fixed.localAddress,
                privateKey = fixed.privateKey,
                peerPublicKey = fixed.peerPublicKey,
                preSharedKey = fixed.preSharedKey,
                reserved = fixed.reserved,
                peers = fixed.peers
            )

            "ssh" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                user = fixed.user,
                password = fixed.password,
                privateKeyPath = fixed.privateKeyPath,
                privateKeyPassphrase = fixed.privateKeyPassphrase,
                hostKey = fixed.hostKey,
                hostKeyAlgorithms = fixed.hostKeyAlgorithms,
                clientVersion = fixed.clientVersion
            )

            "shadowtls" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                version = fixed.version,
                password = fixed.password,
                detour = fixed.detour
            )

            else -> fixed
        }
    }

    /**
     * 构建自定义规则集配置
     */
    private fun buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig> {
        val ruleSetRepo = RuleSetRepository.getInstance(context)

        val rules = settings.ruleSets.map { ruleSet ->
            if (ruleSet.type == RuleSetType.REMOTE) {
                // 远程规则集：使用预下载的本地缓存
                val localPath = ruleSetRepo.getRuleSetPath(ruleSet.tag)
                val file = File(localPath)
                if (file.exists() && file.length() > 0) {
                    // 简单的文件头检查 (SRS magic: 0x73, 0x72, 0x73, 0x0A or similar, but sing-box is flexible)
                    // 如果文件太小或者内容明显不对（比如 HTML 错误页），则跳过
                    // 这里我们假设小于 100 字节的文件可能是无效的，或者是下载错误
                    if (file.length() < 10) {
                        Log.w(TAG, "Rule set file too small, ignoring: ${ruleSet.tag} (${file.length()} bytes)")
                        return@map null
                    }
                    
                    // 检查文件头是否为 HTML (下载错误常见情况)
                    try {
                        val header = file.inputStream().use { input ->
                            val buffer = ByteArray(64) // 读取更多字节以防前导空格
                            val read = input.read(buffer)
                            if (read > 0) String(buffer, 0, read) else ""
                        }
                        val trimmedHeader = header.trim()
                        if (trimmedHeader.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                            trimmedHeader.startsWith("<html", ignoreCase = true) ||
                            trimmedHeader.startsWith("{")) { // 也是为了防止 JSON 错误信息
                            Log.e(TAG, "Rule set file appears to be invalid (HTML/JSON), ignoring: ${ruleSet.tag}")
                            // 删除无效文件以便下次重新下载
                            file.delete()
                            return@map null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to inspect rule set file header: ${ruleSet.tag}", e)
                    }

                    RuleSetConfig(
                        tag = ruleSet.tag,
                        type = "local",
                        format = ruleSet.format,
                        path = localPath
                    )
                } else {
                    Log.w(TAG, "Rule set file not found or empty: ${ruleSet.tag} ($localPath)")
                    null
                }
            } else {
                // 本地规则集：直接使用用户指定的路径
                val file = File(ruleSet.path)
                if (file.exists() && file.length() > 0) {
                    RuleSetConfig(
                        tag = ruleSet.tag,
                        type = "local",
                        format = ruleSet.format,
                        path = ruleSet.path
                    )
                } else {
                    Log.w(TAG, "Local rule set file not found: ${ruleSet.tag} (${ruleSet.path})")
                    null
                }
            }
        }.filterNotNull().toMutableList()

        if (settings.blockAds) {
            val adBlockTag = "geosite-category-ads-all"
            val adBlockPath = ruleSetRepo.getRuleSetPath(adBlockTag)
            val adBlockFile = File(adBlockPath)

            if (!adBlockFile.exists() || adBlockFile.length() == 0L) {
                try {
                    context.assets.open("rulesets/$adBlockTag.srs").use { input ->
                        adBlockFile.parentFile?.mkdirs()
                        adBlockFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy built-in ad block rule set", e)
                }
            }

            if (adBlockFile.exists() && adBlockFile.length() > 0 && rules.none { it.tag == adBlockTag }) {
                rules.add(
                    RuleSetConfig(
                        tag = adBlockTag,
                        type = "local",
                        format = "binary",
                        path = adBlockPath
                    )
                )
            }
        }

        return rules
    }

    private fun buildCustomDomainRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        fun splitValues(raw: String): List<String> {
            return raw
                .split("\n", "\r", ",", "，")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        fun resolveOutboundTag(mode: RuleSetOutboundMode?, value: String?): String {
            return when (mode ?: RuleSetOutboundMode.PROXY) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(value)
                    if (resolvedTag != null) resolvedTag else defaultProxyTag
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = value
                    val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) tag else defaultProxyTag
                }
                RuleSetOutboundMode.GROUP -> {
                    if (value.isNullOrBlank()) return defaultProxyTag
                    if (outbounds.any { it.tag == value }) value else defaultProxyTag
                }
            }
        }

        return settings.customRules
            .filter { it.enabled }
            .filter {
                it.type == RuleType.DOMAIN ||
                    it.type == RuleType.DOMAIN_SUFFIX ||
                    it.type == RuleType.DOMAIN_KEYWORD
            }
            .mapNotNull { rule ->
                val values = splitValues(rule.value)
                if (values.isEmpty()) return@mapNotNull null

                val mode = rule.outboundMode ?: when (rule.outbound) {
                    OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                    OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                    OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                }

                val outbound = resolveOutboundTag(mode, rule.outboundValue)
                when (rule.type) {
                    RuleType.DOMAIN -> RouteRule(domain = values, outbound = outbound)
                    RuleType.DOMAIN_SUFFIX -> RouteRule(domainSuffix = values, outbound = outbound)
                    RuleType.DOMAIN_KEYWORD -> RouteRule(domainKeyword = values, outbound = outbound)
                    else -> null
                }
            }
    }

    /**
     * 构建自定义规则集路由规则
     */
    private fun buildCustomRuleSetRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        // 记录所有可用的 outbound tags，用于调试
        val availableTags = outbounds.map { it.tag }
        
        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        
        // 对规则集进行排序：更具体的规则应该排在前面
        // 优先级：单节点/分组 > 代理 > 直连 > 拦截
        // 同时，特定服务的规则（如 google, youtube）应该优先于泛化规则（如 geolocation-!cn）
        // 并且只处理有效的规则集
        val sortedRuleSets = settings.ruleSets.filter { it.enabled && it.tag in validTags }.sortedWith(
            compareBy(
                // 泛化规则排后面（如 geolocation-!cn, geolocation-cn）
                { ruleSet ->
                    when {
                        ruleSet.tag.contains("geolocation-!cn") -> 100
                        ruleSet.tag.contains("geolocation-cn") -> 99
                        ruleSet.tag.contains("!cn") -> 98
                        else -> 0
                    }
                },
                // 单节点模式的规则优先
                { ruleSet ->
                    when (ruleSet.outboundMode) {
                        RuleSetOutboundMode.NODE -> 0
                        RuleSetOutboundMode.GROUP -> 1
                        RuleSetOutboundMode.PROXY -> 2
                        RuleSetOutboundMode.DIRECT -> 3
                        RuleSetOutboundMode.BLOCK -> 4
                        RuleSetOutboundMode.PROFILE -> 2
                        null -> 5
                    }
                }
            )
        )
        
        
        sortedRuleSets.forEach { ruleSet ->
            
            val outboundTag = when (ruleSet.outboundMode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(ruleSet.outboundValue)
                    if (resolvedTag != null) {
                         resolvedTag
                    } else {
                         Log.w(TAG, "Node ID '${ruleSet.outboundValue}' not resolved to any tag, falling back to $defaultProxyTag")
                         defaultProxyTag
                    }
                }
                RuleSetOutboundMode.PROFILE -> {
                     val profileId = ruleSet.outboundValue
                     val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                     val tag = "P:$profileName"
                     if (outbounds.any { it.tag == tag }) {
                         tag
                     } else {
                         defaultProxyTag
                     }
                }
                RuleSetOutboundMode.GROUP -> {
                    val groupName = ruleSet.outboundValue
                    if (!groupName.isNullOrEmpty() && outbounds.any { it.tag == groupName }) {
                         groupName
                    } else {
                         defaultProxyTag
                    }
                }
            }

            // 处理入站限制
            val inboundTags = if (ruleSet.inbounds.isNullOrEmpty()) {
                null
            } else {
                // 将简化的 "tun", "mixed" 映射为实际的 inbound tag
                ruleSet.inbounds.map {
                    when(it) {
                        "tun" -> "tun-in"
                        "mixed" -> "mixed-in" // 假设有这个 inbound
                        else -> it
                    }
                }
            }

            rules.add(RouteRule(
                ruleSet = listOf(ruleSet.tag),
                outbound = outboundTag,
                inbound = inboundTags
            ))
            
        }

        return rules
    }

    /**
     * 构建应用分流路由规则
     */
    private fun buildAppRoutingRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        fun resolveUidByPackageName(pkg: String): Int {
            return try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                info.uid
            } catch (_: Exception) {
                0
            }
        }
        
        fun resolveOutboundTag(mode: RuleSetOutboundMode?, value: String?): String {
            return when (mode ?: RuleSetOutboundMode.DIRECT) {
                RuleSetOutboundMode.DIRECT -> "direct"
                RuleSetOutboundMode.BLOCK -> "block"
                RuleSetOutboundMode.PROXY -> defaultProxyTag
                RuleSetOutboundMode.NODE -> {
                    val resolvedTag = nodeTagResolver(value)
                    if (resolvedTag != null) resolvedTag else defaultProxyTag
                }
                RuleSetOutboundMode.PROFILE -> {
                    val profileId = value
                    val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
                    val tag = "P:$profileName"
                    if (outbounds.any { it.tag == tag }) tag else defaultProxyTag
                }
                RuleSetOutboundMode.GROUP -> {
                    if (value.isNullOrBlank()) return defaultProxyTag
                    if (outbounds.any { it.tag == value }) value else defaultProxyTag
                }
            }
        }
        
        // 1. 处理应用规则（单个应用）
        settings.appRules.filter { it.enabled }.forEach { rule ->
            val outboundTag = resolveOutboundTag(rule.outboundMode, rule.outboundValue)

            val uid = resolveUidByPackageName(rule.packageName)
            if (uid > 0) {
                rules.add(
                    RouteRule(
                        userId = listOf(uid),
                        outbound = outboundTag
                    )
                )
            }

            rules.add(
                RouteRule(
                    packageName = listOf(rule.packageName),
                    outbound = outboundTag
                )
            )
            
        }
        
        // 2. 处理应用分组
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val outboundTag = resolveOutboundTag(group.outboundMode, group.outboundValue)
            
            // 将分组中的所有应用包名添加到一条规则中
            val packageNames = group.apps.map { it.packageName }
            if (packageNames.isNotEmpty()) {
                val uids = packageNames.map { resolveUidByPackageName(it) }.filter { it > 0 }.distinct()
                if (uids.isNotEmpty()) {
                    rules.add(
                        RouteRule(
                            userId = uids,
                            outbound = outboundTag
                        )
                    )
                }

                rules.add(
                    RouteRule(
                        packageName = packageNames,
                        outbound = outboundTag
                    )
                )
                
            }
        }
        
        return rules
    }

    private fun buildRunLogConfig(): LogConfig {
        return LogConfig(
            level = "warn",
            timestamp = true
        )
    }

    private fun buildRunExperimentalConfig(settings: AppSettings): ExperimentalConfig {
        // 使用 filesDir 而非 cacheDir，确保 FakeIP 缓存不会被系统清理
        val singboxDataDir = File(context.filesDir, "singbox_data").also { it.mkdirs() }

        // 启用 Clash API 提供额外的保活机制
        // 这会定期发送心跳，防止长连接应用（Telegram等）的TCP连接被NAT设备超时关闭
        val clashApi = ClashApiConfig(
            externalController = "127.0.0.1:9090",
            defaultMode = "rule"
        )

        return ExperimentalConfig(
            cacheFile = CacheFileConfig(
                enabled = true,
                path = File(singboxDataDir, "cache.db").absolutePath,
                storeFakeip = settings.fakeDnsEnabled
            ),
            clashApi = clashApi
        )
    }

    private fun buildRunInbounds(settings: AppSettings): List<Inbound> {
        // 添加入站配置
        val inbounds = mutableListOf<Inbound>()

        // 1. 添加混合入站 (Mixed Port)
        if (settings.proxyPort > 0) {
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
                    listenPort = settings.proxyPort,
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        }

        if (settings.tunEnabled) {
            inbounds.add(
                Inbound(
                    type = "tun",
                    tag = "tun-in",
                    interfaceName = settings.tunInterfaceName,
                    inet4Address = listOf("172.19.0.1/30"),
                    mtu = settings.tunMtu,
                    autoRoute = false, // Handled by Android VpnService
                    strictRoute = false, // Can cause issues on some Android versions
                    // 智能降级逻辑：如果设备不支持 system/mixed 模式（缺少 CAP_NET_RAW 权限），
                    // 自动降级到 gvisor 模式。UI 仍显示用户选择的模式。
                    stack = getEffectiveTunStack(settings.tunStack).name.lowercase(),
                    endpointIndependentNat = settings.endpointIndependentNat,
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        } else if (settings.proxyPort <= 0) {
            // 如果禁用 TUN 且未设置自定义端口，则添加默认混合入站（HTTP+SOCKS），方便本地代理使用
            inbounds.add(
                Inbound(
                    type = "mixed",
                    tag = "mixed-in",
                    listen = "127.0.0.1",
                    listenPort = 2080,
                    sniff = true,
                    sniffOverrideDestination = true,
                    sniffTimeout = "300ms"
                )
            )
        }

        return inbounds
    }

    private fun buildRunDns(settings: AppSettings, validRuleSets: List<RuleSetConfig>): DnsConfig {
        // 添加 DNS 配置
        val dnsServers = mutableListOf<DnsServer>()
        val dnsRules = mutableListOf<DnsRule>()

        // 0. Bootstrap DNS (必须是 IP，用于解析其他 DoH/DoT 域名)
        // 使用多个 IP 以提高可靠性
        // 使用用户配置的服务器地址策略
        val bootstrapStrategy = mapDnsStrategy(settings.serverAddressStrategy) ?: "ipv4_only"
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap",
                address = "223.5.5.5", // AliDNS IP
                detour = "direct",
                strategy = bootstrapStrategy
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap-backup",
                address = "119.29.29.29", // DNSPod IP
                detour = "direct",
                strategy = bootstrapStrategy
            )
        )
        // 也可以使用一个多地址的 Server (如果内核支持)
        
        // 1. 本地 DNS
        val localDnsAddr = settings.localDns.takeIf { it.isNotBlank() } ?: "https://dns.alidns.com/dns-query"
        dnsServers.add(
            DnsServer(
                tag = "local",
                address = localDnsAddr,
                detour = "direct",
                strategy = mapDnsStrategy(settings.directDnsStrategy),
                addressResolver = "dns-bootstrap"
            )
        )

        // 2. 远程 DNS (走代理)
        val remoteDnsAddr = settings.remoteDns.takeIf { it.isNotBlank() } ?: "https://dns.google/dns-query"
        dnsServers.add(
            DnsServer(
                tag = "remote",
                address = remoteDnsAddr,
                detour = "PROXY",
                strategy = mapDnsStrategy(settings.remoteDnsStrategy),
                addressResolver = "dns-bootstrap" // 必须指定解析器
            )
        )

        if (settings.fakeDnsEnabled) {
            dnsServers.add(
                DnsServer(
                    tag = "fakeip-dns",
                    address = "fakeip"
                )
            )
        }

        // 3. 备用公共 DNS (直接连接，用于 bootstrap 和兜底)
        dnsServers.add(
            DnsServer(
                tag = "google-dns",
                address = "8.8.8.8",
                detour = "direct"
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "cloudflare-dns",
                address = "1.1.1.1",
                detour = "direct"
            )
        )

        // 4. 备用国内 DNS
        dnsServers.add(
            DnsServer(
                tag = "dnspod",
                address = "119.29.29.29",
                detour = "direct",
                strategy = mapDnsStrategy(settings.directDnsStrategy)
            )
        )

        // 优化：代理类域名的 DNS 处理
        val proxyRuleSets = mutableListOf<String>()
        val possibleProxyTags = listOf(
            "geosite-geolocation-!cn", "geosite-google", "geosite-openai", 
            "geosite-youtube", "geosite-telegram", "geosite-github", 
            "geosite-twitter", "geosite-netflix", "geosite-apple",
            "geosite-facebook", "geosite-instagram", "geosite-tiktok",
            "geosite-disney", "geosite-microsoft", "geosite-amazon"
        )
        possibleProxyTags.forEach { tag ->
            // 只添加有效且存在的规则集
            if (validRuleSets.any { it.tag == tag }) proxyRuleSets.add(tag)
        }

        if (proxyRuleSets.isNotEmpty()) {
            if (settings.fakeDnsEnabled) {
                // 如果开启了 FakeIP，代理域名必须返回 FakeIP 以支持域名分流规则
                dnsRules.add(
                    DnsRule(
                        ruleSet = proxyRuleSets,
                        server = "fakeip-dns"
                    )
                )
            } else {
                // 未开启 FakeIP，则使用远程 DNS
                dnsRules.add(
                    DnsRule(
                        ruleSet = proxyRuleSets,
                        server = "remote"
                    )
                )
            }
        }

        // 优化：直连/绕过类域名的 DNS 强制走 local
        val directRuleSets = mutableListOf<String>()
        if (validRuleSets.any { it.tag == "geosite-cn" }) directRuleSets.add("geosite-cn")
        
        if (directRuleSets.isNotEmpty()) {
            dnsRules.add(
                DnsRule(
                    ruleSet = directRuleSets,
                    server = "local"
                )
            )
        }
        
        // 5. 应用特定 DNS 规则（确保应用分流的应用 DNS 走正确的服务器）
        val appPackagesForDns = (settings.appRules.filter { it.enabled }.map { it.packageName } +
                settings.appGroups.filter { it.enabled }.flatMap { it.apps.map { it.packageName } }).distinct()
        
        if (appPackagesForDns.isNotEmpty()) {
            val serverTag = if (settings.fakeDnsEnabled) "fakeip-dns" else "remote"
            val uids = appPackagesForDns.map {
                try {
                    context.packageManager.getApplicationInfo(it, 0).uid
                } catch (_: Exception) {
                    0
                }
            }.filter { it > 0 }.distinct()

            if (uids.isNotEmpty()) {
                dnsRules.add(
                    0,
                    DnsRule(
                        userId = uids,
                        server = serverTag
                    )
                )
            }

            dnsRules.add(
                if (uids.isNotEmpty()) 1 else 0,
                DnsRule(
                    packageName = appPackagesForDns,
                    server = serverTag
                )
            )
        }
        
        // Fake DNS 兜底
        if (settings.fakeDnsEnabled) {
            dnsRules.add(
                DnsRule(
                    queryType = listOf("A", "AAAA"),
                    server = "fakeip-dns"
                )
            )
        }

        val fakeIpConfig = if (settings.fakeDnsEnabled) {
            // 解析用户配置的 fakeIpRange，支持同时指定 IPv4 和 IPv6 范围
            // 格式: "198.18.0.0/15" 或 "198.18.0.0/15,fc00::/18"
            val fakeIpRanges = settings.fakeIpRange.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val inet4Range = fakeIpRanges.firstOrNull { it.contains(".") } ?: "198.18.0.0/15"
            val inet6Range = fakeIpRanges.firstOrNull { it.contains(":") } ?: "fc00::/18"
            
            DnsFakeIpConfig(
                enabled = true,
                inet4Range = inet4Range,
                inet6Range = inet6Range
            )
        } else {
            null
        }

        return DnsConfig(
            servers = dnsServers,
            rules = dnsRules,
            finalServer = "local", // 兜底使用本地 DNS
            strategy = mapDnsStrategy(settings.dnsStrategy),
            disableCache = !settings.dnsCacheEnabled,
            independentCache = true,
            fakeip = fakeIpConfig
        )
    }

    private data class RunOutboundsContext(
        val outbounds: List<Outbound>,
        val selectorTag: String,
        val nodeTagResolver: (String?) -> String?,
        val nodeTagMap: Map<String, String>
    )

    private fun buildRunOutbounds(
        baseConfig: SingBoxConfig,
        activeNode: NodeUi?,
        settings: AppSettings,
        allNodes: List<NodeUi>
    ): RunOutboundsContext {
        val rawOutbounds = baseConfig.outbounds
        if (rawOutbounds.isNullOrEmpty()) {
            Log.w(TAG, "No outbounds found in base config, adding defaults")
        }

        val fixedOutbounds = rawOutbounds?.map { outbound ->
            buildOutboundForRuntime(outbound)
        }?.toMutableList() ?: mutableListOf()

        if (fixedOutbounds.none { it.tag == "direct" }) {
            fixedOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        if (fixedOutbounds.none { it.tag == "block" }) {
            fixedOutbounds.add(Outbound(type = "block", tag = "block"))
        }
        if (fixedOutbounds.none { it.tag == "dns-out" }) {
            fixedOutbounds.add(Outbound(type = "dns", tag = "dns-out"))
        }

        // --- 处理跨配置节点引用 ---
        val activeProfileId = _activeProfileId.value
        val requiredNodeIds = mutableSetOf<String>()
        val requiredGroupNames = mutableSetOf<String>()
        val requiredProfileIds = mutableSetOf<String>()

        fun resolveNodeRefToId(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("::", limit = 2)
            if (parts.size == 2) {
                val profileId = parts[0]
                val nodeName = parts[1]
                return allNodes.firstOrNull { it.sourceProfileId == profileId && it.name == nodeName }?.id
            }
            if (allNodes.any { it.id == value }) return value
            val node = if (activeProfileId != null) {
                allNodes.firstOrNull { it.sourceProfileId == activeProfileId && it.name == value }
                    ?: allNodes.firstOrNull { it.name == value }
            } else {
                allNodes.firstOrNull { it.name == value }
            }
            return node?.id
        }

        // 收集所有规则中引用的节点 ID、组名称和配置 ID
        settings.appRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.GROUP -> rule.outboundValue?.let { requiredGroupNames.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            when (group.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(group.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.GROUP -> group.outboundValue?.let { requiredGroupNames.add(it) }
                RuleSetOutboundMode.PROFILE -> group.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.ruleSets.filter { it.enabled }.forEach { ruleSet ->
            when (ruleSet.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(ruleSet.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.GROUP -> ruleSet.outboundValue?.let { requiredGroupNames.add(it) }
                RuleSetOutboundMode.PROFILE -> ruleSet.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }

        // 确保当前选中的节点始终可用
        activeNode?.let { requiredNodeIds.add(it.id) }

        // 将所需组和配置中的所有节点 ID 也加入到 requiredNodeIds
        requiredGroupNames.forEach { groupName ->
            allNodes.filter { it.group == groupName }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }
        requiredProfileIds.forEach { profileId ->
            allNodes.filter { it.sourceProfileId == profileId }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }

        // 建立 NodeID -> OutboundTag 的映射
        val nodeTagMap = mutableMapOf<String, String>()
        val existingTags = fixedOutbounds.map { it.tag }.toMutableSet()

        // 1. 先映射当前配置中的节点
        if (activeProfileId != null) {
            allNodes.filter { it.sourceProfileId == activeProfileId }.forEach { node ->
                if (existingTags.contains(node.name)) {
                    nodeTagMap[node.id] = node.name
                }
            }
        }

        // 2. 处理需要引入的外部节点
        requiredNodeIds.forEach { nodeId ->
            if (nodeTagMap.containsKey(nodeId)) return@forEach // 已经在当前配置中

            val node = allNodes.find { it.id == nodeId } ?: return@forEach
            val sourceProfileId = node.sourceProfileId

            // 如果是当前配置但没找到tag(可能改名了?), 跳过
            if (sourceProfileId == activeProfileId) return@forEach

            // 加载外部配置
            val sourceConfig = loadConfig(sourceProfileId) ?: return@forEach
            val sourceOutbound = sourceConfig.outbounds?.find { it.tag == node.name } ?: return@forEach

            // 运行时修复
            var fixedSourceOutbound = buildOutboundForRuntime(sourceOutbound)

            // 处理标签冲突
            var finalTag = fixedSourceOutbound.tag
            if (existingTags.contains(finalTag)) {
                // 冲突，生成新标签: Name_ProfileSuffix
                val suffix = sourceProfileId.take(4)
                finalTag = "${finalTag}_$suffix"
                // 如果还冲突 (极小概率), 再加随机
                if (existingTags.contains(finalTag)) {
                    finalTag = "${finalTag}_${java.util.UUID.randomUUID().toString().take(4)}"
                }
                fixedSourceOutbound = fixedSourceOutbound.copy(tag = finalTag)
            }

            // 添加到 outbounds
            fixedOutbounds.add(fixedSourceOutbound)
            existingTags.add(finalTag)
            nodeTagMap[nodeId] = finalTag

        }

        // 3. 处理需要的节点组 (Merge/Create selectors)
        requiredGroupNames.forEach { groupName ->
            val nodesInGroup = allNodes.filter { it.group == groupName }
            val nodeTags = nodesInGroup.mapNotNull { nodeTagMap[it.id] }

            if (nodeTags.isNotEmpty()) {
                val existingIndex = fixedOutbounds.indexOfFirst { it.tag == groupName }
                if (existingIndex >= 0) {
                    val existing = fixedOutbounds[existingIndex]
                    if (existing.type == "selector" || existing.type == "urltest") {
                        // Merge tags: existing + new (deduplicated)
                        val combinedTags = ((existing.outbounds ?: emptyList()) + nodeTags).distinct()
                        // 确保列表不为空
                        val safeTags = if (combinedTags.isEmpty()) listOf("direct") else combinedTags
                        val safeDefault = existing.default?.takeIf { it in safeTags } ?: safeTags.firstOrNull()
                        fixedOutbounds[existingIndex] = existing.copy(outbounds = safeTags, default = safeDefault)
                    } else {
                        Log.w(TAG, "Tag collision: '$groupName' is needed as group but exists as ${existing.type}")
                    }
                } else {
                    // Create new selector
                    val newSelector = Outbound(
                        type = "selector",
                        tag = groupName,
                        outbounds = nodeTags.distinct(),
                        default = nodeTags.firstOrNull(),
                        interruptExistConnections = false
                    )
                    // Insert at beginning to ensure visibility/precedence
                    fixedOutbounds.add(0, newSelector)
                }
            }
        }

        // 4. 处理需要的配置 (Create Profile selectors)
        requiredProfileIds.forEach { profileId ->
            val profileNodes = allNodes.filter { it.sourceProfileId == profileId }
            val nodeTags = profileNodes.mapNotNull { nodeTagMap[it.id] }
            val profileName = _profiles.value.find { it.id == profileId }?.name ?: "Profile_$profileId"
            val tag = "P:$profileName" // 使用 P: 前缀区分配置选择器

            if (nodeTags.isNotEmpty()) {
                val existingIndex = fixedOutbounds.indexOfFirst { it.tag == tag }
                if (existingIndex < 0) {
                    val newSelector = Outbound(
                        type = "selector",
                        tag = tag,
                        outbounds = nodeTags.distinct(),
                        default = nodeTags.firstOrNull(),
                        interruptExistConnections = false
                    )
                    fixedOutbounds.add(0, newSelector)
                }
            }
        }

        // 收集所有代理节点名称 (包括新添加的外部节点)
        // 2025-fix: 扩展支持的协议列表，防止 wireguard/ssh/shadowtls/http/socks 等被排除在 PROXY 组之外
        val proxyTags = fixedOutbounds.filter {
            it.type in listOf(
                "vless", "vmess", "trojan", "shadowsocks",
                "hysteria2", "hysteria", "anytls", "tuic",
                "wireguard", "ssh", "shadowtls", "http", "socks"
            )
        }.map { it.tag }.toMutableList()

        // 创建一个主 Selector
        val selectorTag = "PROXY"

        // 确保代理列表不为空，否则 Selector/URLTest 会崩溃
        if (proxyTags.isEmpty()) {
            proxyTags.add("direct")
        }

        val selectorDefault = activeNode
            ?.let { nodeTagMap[it.id] ?: it.name }
            ?.takeIf { it in proxyTags }
            ?: proxyTags.firstOrNull()

        val selectorOutbound = Outbound(
            type = "selector",
            tag = selectorTag,
            outbounds = proxyTags,
            default = selectorDefault, // 设置默认选中项（确保存在于 outbounds 中）
            interruptExistConnections = true // 切换节点时断开现有连接，确保立即生效
        )

        // 避免重复 tag：订阅配置通常已自带 PROXY selector
        // 若已存在同 tag outbound，直接替换（并删除多余重复项）
        val existingProxyIndexes = fixedOutbounds.withIndex()
            .filter { it.value.tag == selectorTag }
            .map { it.index }
        if (existingProxyIndexes.isNotEmpty()) {
            existingProxyIndexes.asReversed().forEach { idx ->
                fixedOutbounds.removeAt(idx)
            }
        }

        // 将 Selector 添加到 outbounds 列表的最前面（或者合适的位置）
        fixedOutbounds.add(0, selectorOutbound)


        // 定义节点标签解析器
        val nodeTagResolver: (String?) -> String? = { value ->
            if (value.isNullOrBlank()) {
                null
            } else {
                nodeTagMap[value]
                    ?: resolveNodeRefToId(value)?.let { nodeTagMap[it] }
                    ?: if (fixedOutbounds.any { it.tag == value }) value else null
            }
        }

        // Final safety check: Filter out non-existent references in Selector/URLTest
        val allOutboundTags = fixedOutbounds.map { it.tag }.toSet()
        val safeOutbounds = fixedOutbounds.map { outbound ->
            if (outbound.type == "selector" || outbound.type == "urltest" || outbound.type == "url-test") {
                val validRefs = outbound.outbounds?.filter { allOutboundTags.contains(it) } ?: emptyList()
                val safeRefs = if (validRefs.isEmpty()) listOf("direct") else validRefs

                if (safeRefs.size != (outbound.outbounds?.size ?: 0)) {
                    Log.w(TAG, "Filtered invalid refs in ${outbound.tag}: ${outbound.outbounds} -> $safeRefs")
                }
                
                // Ensure default is valid
                val currentDefault = outbound.default
                val safeDefault = if (currentDefault != null && safeRefs.contains(currentDefault)) {
                    currentDefault
                } else {
                    safeRefs.firstOrNull()
                }
                
                outbound.copy(outbounds = safeRefs, default = safeDefault)
            } else {
                outbound
            }
        }

        return RunOutboundsContext(
            outbounds = safeOutbounds,
            selectorTag = selectorTag,
            nodeTagResolver = nodeTagResolver,
            nodeTagMap = nodeTagMap
        )
    }

    private fun buildRunRoute(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): RouteConfig {
        // 构建应用分流规则
        val appRoutingRules = buildAppRoutingRules(settings, selectorTag, outbounds, nodeTagResolver)

        // 构建自定义规则集路由规则（只针对有效的规则集）
        val customRuleSetRules = buildCustomRuleSetRules(settings, selectorTag, outbounds, nodeTagResolver, validRuleSets)

        val quicRule = if (settings.blockQuic) {
            listOf(RouteRule(protocolRaw = listOf("quic"), outbound = "block"))
        } else {
            emptyList()
        }

        // 局域网绕过规则
        val bypassLanRules = if (settings.bypassLan) {
            listOf(
                RouteRule(
                    ipCidr = listOf(
                        "10.0.0.0/8",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "fc00::/7",
                        "127.0.0.0/8",
                        "::1/128"
                    ),
                    outbound = "direct"
                )
            )
        } else {
            emptyList()
        }

        val dnsTrafficRule = listOf(RouteRule(protocolRaw = listOf("dns"), outbound = "dns-out"))

        val adBlockEnabled = settings.blockAds && validRuleSets.any { it.tag == "geosite-category-ads-all" }
        val adBlockRules = if (adBlockEnabled) {
            listOf(RouteRule(ruleSet = listOf("geosite-category-ads-all"), outbound = "block"))
        } else {
            emptyList()
        }

        val customDomainRules = buildCustomDomainRules(settings, selectorTag, outbounds, nodeTagResolver)

        val defaultRuleCatchAll = when (settings.defaultRule) {
            DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
            DefaultRule.BLOCK -> listOf(RouteRule(outbound = "block"))
            DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
        }

        val allRules = when (settings.routingMode) {
            RoutingMode.GLOBAL_PROXY -> dnsTrafficRule + adBlockRules
            RoutingMode.GLOBAL_DIRECT -> dnsTrafficRule + adBlockRules + listOf(RouteRule(outbound = "direct"))
            RoutingMode.RULE -> {
                dnsTrafficRule + adBlockRules + quicRule + bypassLanRules + appRoutingRules + customDomainRules + customRuleSetRules + defaultRuleCatchAll
            }
        }

        // 记录所有生成的路由规则
        allRules.forEachIndexed { index, rule ->
            val ruleDesc = buildString {
                rule.protocolRaw?.let { append("protocol=$it ") }
                rule.ruleSet?.let { append("ruleSet=$it ") }
                rule.packageName?.let { append("pkg=$it ") }
                rule.domain?.let { append("domain=$it ") }
                rule.inbound?.let { append("inbound=$it ") }
                append("-> ${rule.outbound}")
            }
        }

        return RouteConfig(
            ruleSet = validRuleSets,
            rules = allRules,
            finalOutbound = selectorTag, // 路由指向 Selector
            findProcess = true,
            autoDetectInterface = true
        )
    }
    
    /**
     * 获取当前活跃配置的原始 JSON
     */
    fun getActiveConfig(): SingBoxConfig? {
        val id = _activeProfileId.value ?: return null
        return loadConfig(id)
    }
    
    /**
     * 获取指定配置的原始 JSON
     */
    fun getConfig(profileId: String): SingBoxConfig? {
        return loadConfig(profileId)
    }
    
    private fun mapDnsStrategy(strategy: DnsStrategy): String? {
        return when (strategy) {
            DnsStrategy.AUTO -> null
            DnsStrategy.PREFER_IPV4 -> "prefer_ipv4"
            DnsStrategy.PREFER_IPV6 -> "prefer_ipv6"
            DnsStrategy.ONLY_IPV4 -> "ipv4_only"
            DnsStrategy.ONLY_IPV6 -> "ipv6_only"
        }
    }

    /**
     * 根据设置中的 IP 地址解析并修复 Outbound
     */
    fun getOutboundByNodeId(nodeId: String): Outbound? {
        val node = _nodes.value.find { it.id == nodeId } ?: return null
        val config = loadConfig(node.sourceProfileId) ?: return null
        return config.outbounds?.find { it.tag == node.name }
    }
    
    /**
     * 根据节点ID获取NodeUi
     * 优先从当前配置的节点中查找，如果找不到则从所有已加载的配置中查找
     */
    fun getNodeById(nodeId: String): NodeUi? {
        // 首先在当前配置的节点中查找
        _nodes.value.find { it.id == nodeId }?.let { return it }

        // 如果当前配置中没有，尝试从所有已加载的配置中查找
        // 这样可以确保即使配置切换时也能正确显示节点名称
        for ((_, nodes) in profileNodes) {
            nodes.find { it.id == nodeId }?.let { return it }
        }

        // 最后尝试从 allNodes 中查找（如果已加载）
        _allNodes.value.find { it.id == nodeId }?.let { return it }

        return null
    }
    
    /**
     * 删除节点
     */
    fun deleteNode(nodeId: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 过滤掉要删除的节点
        val newOutbounds = config.outbounds?.filter { it.tag != node.name }
        val newConfig = config.copy(outbounds = newOutbounds)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes
        updateAllNodesAndGroups()

        // 保存文件
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果是当前活跃配置，更新UI状态
        if (_activeProfileId.value == profileId) {
            _nodes.value = newNodes
            updateNodeGroups(newNodes)
            
            // 如果删除的是当前选中节点，重置选中
            if (_activeNodeId.value == nodeId) {
                _activeNodeId.value = newNodes.firstOrNull()?.id
            }
        }
        
        saveProfiles()
    }

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
            // 1. 使用 ConfigRepository 统一的 parseNodeLink 解析链接，确保解析逻辑一致
            val outbound = parseNodeLink(link.trim())
                ?: return@withContext Result.failure(Exception("Failed to parse node link"))
            
            // 2. 查找或创建"手动添加"配置
            val manualProfileName = "Manual"
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
            
            // 确保没有其他重复
            val newConfig = deduplicateTags(SingBoxConfig(outbounds = newOutbounds))
            
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
            
            Log.i(TAG, "Added single node: $finalTag to profile $profileId")
            
            Result.success(addedNode ?: nodes.last())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add single node", e)
            Result.failure(Exception(context.getString(R.string.nodes_add_failed) + ": ${e.message}"))
        }
    }

    /**
     * 重命名节点
     */
    fun renameNode(nodeId: String, newName: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 更新对应节点的 tag
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) it.copy(tag = newName) else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes
        updateAllNodesAndGroups()

        // 保存文件
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果是当前活跃配置，更新UI状态
        if (_activeProfileId.value == profileId) {
            _nodes.value = newNodes
            updateNodeGroups(newNodes)
            
            // 如果重命名的是当前选中节点，更新 activeNodeId
            if (_activeNodeId.value == nodeId) {
                val newNode = newNodes.find { it.name == newName }
                if (newNode != null) {
                    _activeNodeId.value = newNode.id
                }
            }
        }
        
        saveProfiles()
    }

    /**
     * 更新节点配置
     */
    fun updateNode(nodeId: String, newOutbound: Outbound) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return

        // 更新对应节点
        // 注意：这里假设 newOutbound.tag 已经包含了可能的新名称
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) newOutbound else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)

        // 更新内存中的配置
        cacheConfig(profileId, newConfig)
        
        // 重新提取节点列表
        val newNodes = extractNodesFromConfig(newConfig, profileId)
        profileNodes[profileId] = newNodes
        updateAllNodesAndGroups()

        // 保存文件
        try {
            val configFile = File(configDir, "$profileId.json")
            configFile.writeText(gson.toJson(newConfig))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果是当前活跃配置，更新UI状态
        if (_activeProfileId.value == profileId) {
            _nodes.value = newNodes
            updateNodeGroups(newNodes)
            
            // 如果更新的是当前选中节点，尝试恢复选中状态
            if (_activeNodeId.value == nodeId) {
                val newNode = newNodes.find { it.name == newOutbound.tag }
                if (newNode != null) {
                    _activeNodeId.value = newNode.id
                }
            }
        }
        
        saveProfiles()
    }

    /**
     * 导出节点链接
     */
    fun exportNode(nodeId: String): String? {
        val node = _nodes.value.find { it.id == nodeId } ?: run {
            Log.e(TAG, "exportNode: Node not found in UI list: $nodeId")
            return null
        }

        val config = loadConfig(node.sourceProfileId) ?: run {
            Log.e(TAG, "exportNode: Config not found for profile: ${node.sourceProfileId}")
            return null
        }

        val outbound = config.outbounds?.find { it.tag == node.name } ?: run {
            Log.e(TAG, "exportNode: Outbound not found in config with tag: ${node.name}")
            return null
        }


        val link = when (outbound.type) {
            "vless" -> generateVLessLink(outbound)
            "vmess" -> generateVMessLink(outbound)
            "shadowsocks" -> generateShadowsocksLink(outbound)
            "trojan" -> generateTrojanLink(outbound)
            "hysteria2" -> {
                val hy2 = generateHysteria2Link(outbound)
                hy2
            }
            "hysteria" -> generateHysteriaLink(outbound)
            "anytls" -> generateAnyTLSLink(outbound)
            "tuic" -> generateTuicLink(outbound)
            else -> {
                Log.e(TAG, "exportNode: Unsupported type ${outbound.type}")
                null
            }
        }

        return link?.takeIf { it.isNotBlank() }
    }

    private fun encodeUrlComponent(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun formatServerHost(server: String): String {
        val s = server.trim()
        return if (s.contains(":") && !s.startsWith("[") && !s.endsWith("]")) {
            "[$s]"
        } else {
            s
        }
    }

    private fun buildOptionalQuery(params: List<String>): String {
        val query = params.filter { it.isNotBlank() }.joinToString("&")
        return if (query.isNotEmpty()) "?$query" else ""
    }

    private fun generateVLessLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val params = mutableListOf<String>()
        
        params.add("type=${outbound.transport?.type ?: "tcp"}")
        params.add("encryption=none")
        
        outbound.flow?.let { params.add("flow=$it") }
        
        if (outbound.tls?.enabled == true) {
            if (outbound.tls.reality?.enabled == true) {
                 params.add("security=reality")
                 outbound.tls.reality.publicKey?.let { params.add("pbk=${encodeUrlComponent(it)}") }
                 outbound.tls.reality.shortId?.let { params.add("sid=${encodeUrlComponent(it)}") }
                 outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            } else {
                 params.add("security=tls")
                 outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            }
            outbound.tls.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
            if (outbound.tls.insecure == true) {
                params.add("allowInsecure=1")
            }
            outbound.tls.alpn?.let { 
                if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}") 
            }
        } else {
             // params.add("security=none") // default is none
        }
        
        outbound.packetEncoding?.let { params.add("packetEncoding=$it") }
        
        // Transport specific
        when (outbound.transport?.type) {
            "ws" -> {
                val host = outbound.transport.headers?.get("Host") 
                    ?: outbound.transport.host?.firstOrNull()
                host?.let { params.add("host=${encodeUrlComponent(it)}") }
                
                var path = outbound.transport.path ?: "/"
                // Handle early data (ed)
                outbound.transport.maxEarlyData?.let { ed ->
                    if (ed != 0) { // Only add if not 0, though usually it's 2048 or something
                        val separator = if (path.contains("?")) "&" else "?"
                        path = "$path${separator}ed=$ed"
                    }
                }
                
                params.add("path=${encodeUrlComponent(path)}") 
            }
            "grpc" -> {
                outbound.transport.serviceName?.let { 
                    params.add("serviceName=${encodeUrlComponent(it)}") 
                }
                params.add("mode=gun")
            }
            "http", "h2" -> {
                 outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                 outbound.transport.host?.firstOrNull()?.let { params.add("host=${encodeUrlComponent(it)}") }
            }
        }

        val name = encodeUrlComponent(outbound.tag)
        val queryPart = buildOptionalQuery(params)
        return "vless://$uuid@$server:$port${queryPart}#$name"
    }

    private fun generateVMessLink(outbound: Outbound): String {
        // Simple implementation for VMess
        try {
            val json = VMessLinkConfig(
                v = "2",
                ps = outbound.tag,
                add = outbound.server,
                port = outbound.serverPort?.toString(),
                id = outbound.uuid,
                aid = "0", // sing-box 只支持 alterId=0
                scy = outbound.security,
                net = outbound.transport?.type ?: "tcp",
                type = "none",
                host = outbound.transport?.headers?.get("Host") ?: outbound.transport?.host?.firstOrNull() ?: "",
                path = outbound.transport?.path ?: "",
                tls = if (outbound.tls?.enabled == true) "tls" else "",
                sni = outbound.tls?.serverName ?: "",
                alpn = outbound.tls?.alpn?.joinToString(","),
                fp = outbound.tls?.utls?.fingerprint
            )
            val jsonStr = gson.toJson(json)
            val base64 = Base64.encodeToString(jsonStr.toByteArray(), Base64.NO_WRAP)
            return "vmess://$base64"
        } catch (e: Exception) {
            return ""
        }
    }

    private fun generateShadowsocksLink(outbound: Outbound): String {
        val method = outbound.method ?: return ""
        val password = outbound.password ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: return ""
        val userInfo = "$method:$password"
        val encodedUserInfo = Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)
        val serverPart = "$server:$port"
        val name = encodeUrlComponent(outbound.tag)
        return "ss://$encodedUserInfo@$serverPart#$name"
    }
    
    private fun generateTrojanLink(outbound: Outbound): String {
         val password = encodeUrlComponent(outbound.password ?: "")
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         if (outbound.tls?.enabled == true) {
             params.add("security=tls")
             outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
             if (outbound.tls.insecure == true) params.add("allowInsecure=1")
         }

         val queryPart = buildOptionalQuery(params)
         return "trojan://$password@$server:$port${queryPart}#$name"
    }

    private fun generateHysteria2Link(outbound: Outbound): String {
         val password = encodeUrlComponent(outbound.password ?: "")
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         
         outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
         if (outbound.tls?.insecure == true) params.add("insecure=1")
         
         outbound.obfs?.let { obfs ->
             obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
             obfs.password?.let { params.add("obfs-password=${encodeUrlComponent(it)}") }
         }

         val queryPart = buildOptionalQuery(params)
         return "hysteria2://$password@$server:$port${queryPart}#$name"
    }

    private fun generateHysteriaLink(outbound: Outbound): String {
         val server = outbound.server?.let { formatServerHost(it) } ?: return ""
         val port = outbound.serverPort ?: 443
         val name = encodeUrlComponent(outbound.tag)
         
         val params = mutableListOf<String>()
         outbound.authStr?.let { params.add("auth=${encodeUrlComponent(it)}") }
         outbound.upMbps?.let { params.add("upmbps=$it") }
         outbound.downMbps?.let { params.add("downmbps=$it") }
         
         outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
         if (outbound.tls?.insecure == true) params.add("insecure=1")
         outbound.tls?.alpn?.let { 
             if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}") 
         }
         
         outbound.obfs?.let { obfs ->
             obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
         }

         val queryPart = buildOptionalQuery(params)
         return "hysteria://$server:$port${queryPart}#$name"
    }
    
    /**
     * 生成 AnyTLS 链接
     */
    private fun generateAnyTLSLink(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)
        
        val params = mutableListOf<String>()
        
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
        
        outbound.idleSessionCheckInterval?.let { params.add("idle_session_check_interval=$it") }
        outbound.idleSessionTimeout?.let { params.add("idle_session_timeout=$it") }
        outbound.minIdleSession?.let { params.add("min_idle_session=$it") }
        
        val queryPart = buildOptionalQuery(params)
        return "anytls://$password@$server:$port${queryPart}#$name"
    }
    
    /**
     * 生成 TUIC 链接
     */
    private fun generateTuicLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: ""
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)
        
        val params = mutableListOf<String>()
        
        outbound.congestionControl?.let { params.add("congestion_control=${encodeUrlComponent(it)}") }
        outbound.udpRelayMode?.let { params.add("udp_relay_mode=${encodeUrlComponent(it)}") }
        if (outbound.zeroRttHandshake == true) params.add("reduce_rtt=1")
        
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("allow_insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
        
        val queryPart = buildOptionalQuery(params)
        return "tuic://$uuid:$password@$server:$port${queryPart}#$name"
    }

    /**
     * 去除重复的 outbound tag
     */
    private fun deduplicateTags(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: return config
        val seenTags = mutableSetOf<String>()
        
        val newOutbounds = outbounds.map { outbound ->
            var tag = outbound.tag
            // 处理空 tag
            if (tag.isBlank()) {
                tag = "unnamed"
            }
            
            var newTag = tag
            var counter = 1
            
            // 如果 tag 已经存在，则添加后缀直到不冲突
            while (seenTags.contains(newTag)) {
                newTag = "${tag}_$counter"
                counter++
            }
            
            seenTags.add(newTag)
            
            if (newTag != outbound.tag) {
                outbound.copy(tag = newTag)
            } else {
                outbound
            }
        }
        
        return config.copy(outbounds = newOutbounds)
    }
}
