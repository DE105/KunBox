package com.kunk.singbox

import android.app.Application
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.SubscriptionAutoUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SingBoxApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        LogRepository.init(this)
        
        // 在应用启动时重新调度所有订阅自动更新任务
        applicationScope.launch {
            SubscriptionAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)
        }
    }
}
