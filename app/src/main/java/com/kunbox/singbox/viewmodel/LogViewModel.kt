package com.kunbox.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunbox.singbox.model.AppSettings
import com.kunbox.singbox.repository.LogRepository
import com.kunbox.singbox.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LogRepository.getInstance()
    private val settingsRepository = SettingsRepository.getInstance(application)

    init {
        // Observe settings to toggle log collection
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                repository.setLogUiActive(settings.debugLoggingEnabled)
            }
        }
    }

    val logs: StateFlow<List<String>> = repository.logs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    fun setDebugLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDebugLoggingEnabled(enabled)
        }
    }

    fun clearLogs() {
        repository.clearLogs()
    }

    fun getLogsForExport(): String {
        return repository.getLogsAsText()
    }

    override fun onCleared() {
        repository.setLogUiActive(false)
        super.onCleared()
    }
}
