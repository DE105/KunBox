package com.kunk.singbox.repository.store

import android.content.Context
import android.util.Log
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.utils.KryoSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 设置存储 - 使用 Kryo 二进制序列化
 *
 * 相比 DataStore + Gson，性能提升 10x+：
 * - Kryo 二进制序列化比 JSON 快 5-10x
 * - 启动时一次性加载，后续内存读取
 * - 异步保存，不阻塞 UI
 *
 * 线程安全：
 * - 使用 Mutex 保护写操作
 * - StateFlow 提供线程安全的读取
 */
class SettingsStore(context: Context) {
    companion object {
        private const val TAG = "SettingsStore"
        private const val SETTINGS_FILE = "settings.kryo"

        @Volatile
        private var INSTANCE: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val settingsFile = File(context.filesDir, SETTINGS_FILE)
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        try {
            if (settingsFile.exists()) {
                val startTime = System.currentTimeMillis()
                val loaded = KryoSerializer.deserializeFromFile<AppSettings>(settingsFile)
                if (loaded != null) {
                    _settings.value = loaded
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Settings loaded from Kryo file in ${elapsed}ms")
                } else {
                    Log.w(TAG, "Kryo deserialization returned null, using defaults")
                }
            } else {
                Log.i(TAG, "No settings file found, using defaults")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings, using defaults", e)
        }
    }

    /**
     * 更新设置 - 同步更新内存，异步保存到文件
     */
    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val newSettings = update(_settings.value)
        _settings.value = newSettings

        // 异步保存
        scope.launch {
            saveSettingsInternal(newSettings)
        }
    }

    /**
     * 更新设置并等待保存完成
     */
    suspend fun updateSettingsAndWait(update: (AppSettings) -> AppSettings) {
        val newSettings = update(_settings.value)
        _settings.value = newSettings
        saveSettingsInternal(newSettings)
    }

    private suspend fun saveSettingsInternal(settings: AppSettings) {
        writeMutex.withLock {
            try {
                val startTime = System.currentTimeMillis()
                val success = KryoSerializer.serializeToFile(settings, settingsFile)
                val elapsed = System.currentTimeMillis() - startTime
                if (success) {
                    Log.d(TAG, "Settings saved to Kryo file in ${elapsed}ms")
                } else {
                    Log.e(TAG, "Failed to save settings to Kryo file")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save settings", e)
            }
        }
    }

    /**
     * 获取当前设置快照
     */
    fun getCurrentSettings(): AppSettings = _settings.value

    /**
     * 强制重新加载设置
     */
    fun reload() {
        loadSettings()
    }

    /**
     * 检查设置文件是否存在
     */
    fun hasSettingsFile(): Boolean = settingsFile.exists()

    /**
     * 删除设置文件（用于重置）
     */
    suspend fun deleteSettingsFile(): Boolean {
        return writeMutex.withLock {
            try {
                if (settingsFile.exists()) {
                    settingsFile.delete()
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete settings file", e)
                false
            }
        }
    }
}
