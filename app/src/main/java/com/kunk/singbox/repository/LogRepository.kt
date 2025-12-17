package com.kunk.singbox.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogRepository private constructor() {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val maxLogSize = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val formattedLog = "[$timestamp] $message"
        
        val currentList = _logs.value.toMutableList()
        currentList.add(formattedLog)
        
        if (currentList.size > maxLogSize) {
            currentList.removeAt(0)
        }
        
        _logs.value = currentList
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    companion object {
        @Volatile
        private var instance: LogRepository? = null

        fun getInstance(): LogRepository {
            return instance ?: synchronized(this) {
                instance ?: LogRepository().also { instance = it }
            }
        }
    }
}
