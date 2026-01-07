package com.kunk.singbox

import android.app.ActivityManager
import android.app.Application
import android.os.Process
import androidx.work.Configuration
import androidx.work.WorkManager
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.service.RuleSetAutoUpdateWorker
import com.kunk.singbox.service.SubscriptionAutoUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SingBoxApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // 手动初始化 WorkManager 以支持多进程
        if (!isWorkManagerInitialized()) {
            WorkManager.initialize(this, workManagerConfiguration)
        }

        LogRepository.init(this)

        // 只在主进程中调度自动更新任务
        if (isMainProcess()) {
            applicationScope.launch {
                // 订阅自动更新
                SubscriptionAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)
                // 规则集自动更新
                RuleSetAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)
            }
        }
    }

    private fun isWorkManagerInitialized(): Boolean {
        return try {
            WorkManager.getInstance(this)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val processName = activityManager.runningAppProcesses?.find { it.pid == pid }?.processName
        return processName == packageName
    }
}
