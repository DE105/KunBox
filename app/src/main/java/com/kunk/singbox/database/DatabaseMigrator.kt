package com.kunk.singbox.database

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.store.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 数据库迁移工具
 *
 * 从 Kryo/JSON 文件迁移到 Room 数据库
 *
 * 迁移策略：
 * 1. 检查是否存在旧的 Kryo/JSON 文件
 * 2. 读取并解析旧数据
 * 3. 转换为 Room 实体并批量插入
 * 4. 标记迁移完成
 */
object DatabaseMigrator {
    private const val TAG = "DatabaseMigrator"
    private const val MIGRATION_COMPLETED_FILE = "db_migration_v1.done"

    // 支持的代理类型
    private val PROXY_TYPES = setOf(
        "shadowsocks", "vmess", "vless", "trojan",
        "hysteria", "hysteria2", "tuic", "wireguard",
        "shadowtls", "ssh", "anytls", "http", "socks"
    )

    /**
     * 检查是否需要迁移
     */
    fun needsMigration(context: Context): Boolean {
        val migrationFile = File(context.filesDir, MIGRATION_COMPLETED_FILE)
        if (migrationFile.exists()) {
            return false
        }

        // 检查是否存在旧数据
        val kryoFile = File(context.filesDir, "profiles.kryo")
        val jsonFile = File(context.filesDir, "profiles.json")
        return kryoFile.exists() || jsonFile.exists()
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
            Log.i(TAG, "Starting database migration from Kryo/JSON to Room")
            val startTime = System.currentTimeMillis()

            val gson = Gson()
            val profileStore = ProfileStore(context, gson)
            val database = AppDatabase.getInstance(context)

            // 1. 加载旧数据
            val savedData = profileStore.loadSavedData()
            if (savedData == null) {
                Log.w(TAG, "No saved data found, skipping migration")
                markMigrationComplete(context)
                return@withContext true
            }

            // 2. 迁移 Profiles
            val profiles = savedData.profiles
            val profileEntities = profiles.mapIndexed { index, profile ->
                ProfileEntity.fromUiModel(profile, sortOrder = index)
            }
            database.profileDao().insertAll(profileEntities)
            Log.i(TAG, "Migrated ${profileEntities.size} profiles")

            // 3. 迁移 Nodes (从配置文件中简单提取)
            var totalNodes = 0
            for (profile in profiles) {
                val config = profileStore.loadConfig(profile.id)
                if (config != null) {
                    val nodes = extractNodesSimple(config, profile.id, profile.name)
                    if (nodes.isNotEmpty()) {
                        val nodeEntities = nodes.mapIndexed { index, node ->
                            NodeEntity.fromUiModel(node, sortOrder = index)
                        }
                        database.nodeDao().insertAll(nodeEntities)
                        totalNodes += nodeEntities.size
                    }
                }
            }
            Log.i(TAG, "Migrated $totalNodes nodes")

            // 4. 迁移活跃状态
            val activeState = ActiveStateEntity(
                id = 1,
                activeProfileId = savedData.activeProfileId,
                activeNodeId = savedData.activeNodeId
            )
            database.activeStateDao().save(activeState)
            Log.i(TAG, "Migrated active state: profile=${savedData.activeProfileId}, node=${savedData.activeNodeId}")

            // 5. 迁移延迟数据
            val latencyEntities = savedData.nodeLatencies.map { (nodeId, latency) ->
                NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
            }
            if (latencyEntities.isNotEmpty()) {
                database.nodeLatencyDao().insertAll(latencyEntities)
                Log.i(TAG, "Migrated ${latencyEntities.size} latency entries")
            }

            // 6. 标记迁移完成
            markMigrationComplete(context)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Database migration completed in ${elapsed}ms")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Database migration failed", e)
            false
        }
    }

    /**
     * 从配置中简单提取节点 (迁移用)
     * 不依赖 NodeExtractor 的复杂逻辑
     */
    private fun extractNodesSimple(
        config: SingBoxConfig,
        profileId: String,
        profileName: String
    ): List<NodeUi> {
        val outbounds = config.outbounds ?: return emptyList()
        return outbounds.mapNotNull { outbound ->
            val type = outbound.type?.lowercase() ?: return@mapNotNull null
            if (type !in PROXY_TYPES) return@mapNotNull null

            val tag = outbound.tag ?: return@mapNotNull null
            val server = outbound.server ?: "unknown"

            NodeUi(
                id = UUID.randomUUID().toString(),
                name = tag,
                protocol = type,
                group = profileName,
                regionFlag = null,
                latencyMs = null,
                isFavorite = false,
                sourceProfileId = profileId,
                tags = emptyList(),
                trafficUsed = 0
            )
        }
    }

    private fun markMigrationComplete(context: Context) {
        try {
            File(context.filesDir, MIGRATION_COMPLETED_FILE).createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create migration marker file", e)
        }
    }

    /**
     * 清除迁移标记（用于调试）
     */
    fun clearMigrationMarker(context: Context) {
        try {
            File(context.filesDir, MIGRATION_COMPLETED_FILE).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete migration marker file", e)
        }
    }
}
