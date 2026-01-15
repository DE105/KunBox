package com.kunk.singbox.repository.store

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置迁移工具 - 从 DataStore + Gson 迁移到 Kryo
 *
 * 迁移策略：
 * 1. 检查是否存在旧的 DataStore 数据
 * 2. 如果存在，读取并转换为 AppSettings
 * 3. 保存到新的 Kryo 文件
 * 4. 标记迁移完成（不删除旧数据，以防回滚）
 */
object SettingsMigrator {
    private const val TAG = "SettingsMigrator"
    private const val MIGRATION_COMPLETED_FILE = "settings_migration_v1.done"

    private val gson = Gson()

    /**
     * 检查是否需要迁移
     */
    fun needsMigration(context: Context): Boolean {
        val migrationFile = File(context.filesDir, MIGRATION_COMPLETED_FILE)
        val kryoFile = File(context.filesDir, "settings.kryo")

        // 如果已完成迁移或 Kryo 文件已存在，则不需要迁移
        if (migrationFile.exists() || kryoFile.exists()) {
            return false
        }

        // 检查 DataStore 文件是否存在
        val dataStoreDir = File(context.filesDir, "datastore")
        val dataStoreFile = File(dataStoreDir, "settings.preferences_pb")
        return dataStoreFile.exists()
    }

    /**
     * 执行迁移
     */
    suspend fun migrate(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!needsMigration(context)) {
            Log.i(TAG, "Migration not needed")
            return@withContext true
        }

        try {
            Log.i(TAG, "Starting settings migration from DataStore to Kryo")
            val startTime = System.currentTimeMillis()

            // 读取 DataStore 数据
            val preferences = context.dataStore.data.first()
            val settings = parsePreferencesToSettings(preferences)

            // 保存到 SettingsStore
            val store = SettingsStore.getInstance(context)
            store.updateSettingsAndWait { settings }

            // 标记迁移完成
            File(context.filesDir, MIGRATION_COMPLETED_FILE).createNewFile()

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Settings migration completed in ${elapsed}ms")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Settings migration failed", e)
            false
        }
    }

    private fun parsePreferencesToSettings(preferences: Preferences): AppSettings {
        // 解析复杂 JSON 字段
        val nodeFilterJson = preferences[stringPreferencesKey("node_filter")]
        val nodeFilter = nodeFilterJson?.let {
            runCatching { gson.fromJson(it, NodeFilter::class.java) }.getOrNull()
        } ?: NodeFilter()

        val nodeSortTypeName = preferences[stringPreferencesKey("node_sort_type")]
        val nodeSortType = nodeSortTypeName?.let {
            runCatching { NodeSortType.valueOf(it) }.getOrNull()
        } ?: NodeSortType.DEFAULT

        val customNodeOrderJson = preferences[stringPreferencesKey("custom_node_order")]
        val customNodeOrder = customNodeOrderJson?.let {
            runCatching {
                gson.fromJson<List<String>>(it, object : TypeToken<List<String>>() {}.type)
            }.getOrNull()
        } ?: emptyList()

        val customRulesJson = preferences[stringPreferencesKey("custom_rules")]
        val customRules = customRulesJson?.let {
            runCatching {
                gson.fromJson<List<CustomRule>>(it, object : TypeToken<List<CustomRule>>() {}.type)
            }.getOrNull()
        } ?: emptyList()

        val ruleSetsJson = preferences[stringPreferencesKey("rule_sets")]
        val ruleSets = ruleSetsJson?.let {
            runCatching {
                gson.fromJson<List<RuleSet>>(it, object : TypeToken<List<RuleSet>>() {}.type)
            }.getOrNull()
        } ?: emptyList()

        val appRulesJson = preferences[stringPreferencesKey("app_rules")]
        val appRules = appRulesJson?.let {
            runCatching {
                gson.fromJson<List<AppRule>>(it, object : TypeToken<List<AppRule>>() {}.type)
            }.getOrNull()
        } ?: emptyList()

        val appGroupsJson = preferences[stringPreferencesKey("app_groups")]
        val appGroups = appGroupsJson?.let {
            runCatching {
                gson.fromJson<List<AppGroup>>(it, object : TypeToken<List<AppGroup>>() {}.type)
            }.getOrNull()
        } ?: emptyList()

        return AppSettings(
            // 通用设置
            autoConnect = preferences[booleanPreferencesKey("auto_connect")] ?: false,
            excludeFromRecent = preferences[booleanPreferencesKey("exclude_from_recent")] ?: false,
            appTheme = parseEnum(preferences[stringPreferencesKey("app_theme")], AppThemeMode.SYSTEM),
            appLanguage = parseEnum(preferences[stringPreferencesKey("app_language")], AppLanguage.SYSTEM),
            showNotificationSpeed = preferences[booleanPreferencesKey("show_notification_speed")] ?: true,

            // TUN/VPN 设置
            tunEnabled = preferences[booleanPreferencesKey("tun_enabled")] ?: true,
            tunStack = parseEnum(preferences[stringPreferencesKey("tun_stack")], TunStack.MIXED),
            tunMtu = preferences[intPreferencesKey("tun_mtu")] ?: 1280,
            tunInterfaceName = preferences[stringPreferencesKey("tun_interface_name")] ?: "tun0",
            autoRoute = preferences[booleanPreferencesKey("auto_route")] ?: false,
            strictRoute = preferences[booleanPreferencesKey("strict_route")] ?: true,
            endpointIndependentNat = preferences[booleanPreferencesKey("endpoint_independent_nat")] ?: false,
            vpnRouteMode = parseEnum(preferences[stringPreferencesKey("vpn_route_mode")], VpnRouteMode.GLOBAL),
            vpnRouteIncludeCidrs = preferences[stringPreferencesKey("vpn_route_include_cidrs")] ?: "",
            vpnAppMode = parseEnum(preferences[stringPreferencesKey("vpn_app_mode")], VpnAppMode.ALL),
            vpnAllowlist = preferences[stringPreferencesKey("vpn_allowlist")] ?: "",
            vpnBlocklist = preferences[stringPreferencesKey("vpn_blocklist")] ?: "",

            // 代理端口设置
            proxyPort = preferences[intPreferencesKey("proxy_port")] ?: 20808,
            allowLan = preferences[booleanPreferencesKey("allow_lan")] ?: false,
            appendHttpProxy = preferences[booleanPreferencesKey("append_http_proxy")] ?: false,

            // DNS 设置
            localDns = preferences[stringPreferencesKey("local_dns")] ?: "https://dns.alidns.com/dns-query",
            remoteDns = preferences[stringPreferencesKey("remote_dns")] ?: "https://dns.google/dns-query",
            fakeDnsEnabled = preferences[booleanPreferencesKey("fake_dns_enabled")] ?: true,
            fakeIpRange = preferences[stringPreferencesKey("fake_ip_range")] ?: "198.18.0.0/15",
            dnsStrategy = parseEnum(preferences[stringPreferencesKey("dns_strategy")], DnsStrategy.PREFER_IPV4),
            remoteDnsStrategy = parseEnum(preferences[stringPreferencesKey("remote_dns_strategy")], DnsStrategy.AUTO),
            directDnsStrategy = parseEnum(preferences[stringPreferencesKey("direct_dns_strategy")], DnsStrategy.AUTO),
            serverAddressStrategy = parseEnum(preferences[stringPreferencesKey("server_address_strategy")], DnsStrategy.AUTO),
            dnsCacheEnabled = preferences[booleanPreferencesKey("dns_cache_enabled")] ?: true,

            // 路由设置
            routingMode = parseEnum(preferences[stringPreferencesKey("routing_mode")], RoutingMode.RULE),
            defaultRule = parseEnum(preferences[stringPreferencesKey("default_rule")], DefaultRule.PROXY),
            blockAds = preferences[booleanPreferencesKey("block_ads")] ?: true,
            bypassLan = preferences[booleanPreferencesKey("bypass_lan")] ?: true,
            blockQuic = preferences[booleanPreferencesKey("block_quic")] ?: true,
            debugLoggingEnabled = preferences[booleanPreferencesKey("debug_logging_enabled")] ?: false,

            // 连接重置设置
            networkChangeResetConnections = preferences[booleanPreferencesKey("network_change_reset_connections")] ?: true,
            wakeResetConnections = preferences[booleanPreferencesKey("wake_reset_connections")] ?: false,

            // 延迟测试设置
            latencyTestMethod = parseEnum(preferences[stringPreferencesKey("latency_test_method")], LatencyTestMethod.REAL_RTT),
            latencyTestUrl = preferences[stringPreferencesKey("latency_test_url")] ?: "https://www.gstatic.com/generate_204",
            latencyTestTimeout = preferences[intPreferencesKey("latency_test_timeout")] ?: 2000,
            latencyTestConcurrency = preferences[intPreferencesKey("latency_test_concurrency")] ?: 10,

            // 镜像设置
            ghProxyMirror = parseEnum(preferences[stringPreferencesKey("gh_proxy_mirror")], GhProxyMirror.SAGERNET_ORIGIN),

            // 高级路由
            customRules = customRules,
            ruleSets = ruleSets,
            appRules = appRules,
            appGroups = appGroups,

            // 规则集自动更新
            ruleSetAutoUpdateEnabled = preferences[booleanPreferencesKey("rule_set_auto_update_enabled")] ?: false,
            ruleSetAutoUpdateInterval = preferences[intPreferencesKey("rule_set_auto_update_interval")] ?: 60,

            // 订阅更新超时
            subscriptionUpdateTimeout = preferences[intPreferencesKey("subscription_update_timeout")] ?: 30,

            // 节点列表设置
            nodeFilter = nodeFilter,
            nodeSortType = nodeSortType,
            customNodeOrder = customNodeOrder,

            // 版本更新设置
            autoCheckUpdate = preferences[booleanPreferencesKey("auto_check_update")] ?: true
        )
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String?, default: T): T {
        if (value.isNullOrBlank()) return default
        return runCatching { enumValueOf<T>(value) }.getOrDefault(default)
    }
}
