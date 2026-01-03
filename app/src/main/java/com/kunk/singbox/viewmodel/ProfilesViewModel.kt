package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {
    
    private val configRepository = ConfigRepository.getInstance(application)

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
    
    // 导入状态
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()
    
    // 单个配置更新状态
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    fun setActiveProfile(profileId: String) {
        configRepository.setActiveProfile(profileId)

        val name = profiles.value.find { it.id == profileId }?.name
        if (!name.isNullOrBlank()) {
            emitToast("已切换到 $name")
        }
    }

    fun toggleProfileEnabled(profileId: String) {
        val before = profiles.value.find { it.id == profileId }
        configRepository.toggleProfileEnabled(profileId)

        val name = before?.name
        if (!name.isNullOrBlank()) {
            val enabledAfter = !(before?.enabled ?: true)
            emitToast(if (enabledAfter) "已启用: $name" else "已禁用: $name")
        }
    }

    fun updateProfileMetadata(profileId: String, newName: String, newUrl: String?, autoUpdateInterval: Int = 0) {
        configRepository.updateProfileMetadata(profileId, newName, newUrl, autoUpdateInterval)
        emitToast("配置已更新")
    }

    fun updateProfile(profileId: String) {
        viewModelScope.launch {
            _updateStatus.value = "正在更新..."
            
            val result = configRepository.updateProfile(profileId)
            
            // 根据结果生成提示消息
            _updateStatus.value = when (result) {
                is SubscriptionUpdateResult.SuccessWithChanges -> {
                    val changes = mutableListOf<String>()
                    if (result.addedCount > 0) changes.add("+${result.addedCount}")
                    if (result.removedCount > 0) changes.add("-${result.removedCount}")
                    "更新成功 (${changes.joinToString("/")}，共${result.totalCount}节点)"
                }
                is SubscriptionUpdateResult.SuccessNoChanges -> {
                    "更新完成，无变化 (${result.totalCount}节点)"
                }
                is SubscriptionUpdateResult.Failed -> {
                    "更新失败: ${result.error}"
                }
            }
            
            delay(2500)
            _updateStatus.value = null
        }
    }

    fun deleteProfile(profileId: String) {
        val name = profiles.value.find { it.id == profileId }?.name
        configRepository.deleteProfile(profileId)
        if (!name.isNullOrBlank()) {
            emitToast("已删除配置: $name")
        } else {
            emitToast("已删除配置")
        }
    }

    /**
     * 导入订阅配置
     */
    fun importSubscription(name: String, url: String, autoUpdateInterval: Int = 0) {
        // 防止重复导入
        if (_importState.value is ImportState.Loading) {
            return
        }
        
        viewModelScope.launch {
            _importState.value = ImportState.Loading("正在获取订阅...")
            
            val result = configRepository.importFromSubscription(
                name = name,
                url = url,
                autoUpdateInterval = autoUpdateInterval,
                onProgress = { progress ->
                    _importState.value = ImportState.Loading(progress)
                }
            )
            
            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                },
                onFailure = { error ->
                    _importState.value = ImportState.Error(error.message ?: "导入失败")
                }
            )
        }
    }

    fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported
    ) {
        if (_importState.value is ImportState.Loading) {
            return
        }
        if (content.isBlank()) {
            _importState.value = ImportState.Error("内容为空")
            return
        }

        viewModelScope.launch {
            _importState.value = ImportState.Loading("正在解析配置...")

            val result = configRepository.importFromContent(
                name = name,
                content = content,
                profileType = profileType,
                onProgress = { progress ->
                    _importState.value = ImportState.Loading(progress)
                }
            )

            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                },
                onFailure = { error ->
                    _importState.value = ImportState.Error(error.message ?: "导入失败")
                }
            )
        }
    }
    
    fun resetImportState() {
        _importState.value = ImportState.Idle
    }

    sealed class ImportState {
        data object Idle : ImportState()
        data class Loading(val message: String) : ImportState()
        data class Success(val profile: ProfileUi) : ImportState()
        data class Error(val message: String) : ImportState()
    }
}