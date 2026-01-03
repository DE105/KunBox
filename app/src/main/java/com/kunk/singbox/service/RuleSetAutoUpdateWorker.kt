package com.kunk.singbox.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 规则集自动更新 Worker
 * 使用 WorkManager 在后台定期更新所有远程规则集
 */
class RuleSetAutoUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "RuleSetAutoUpdate"
        private const val WORK_NAME = "ruleset_global_auto_update"
        
        /**
         * 调度全局规则集自动更新任务
         * @param context Context
         * @param intervalMinutes 更新间隔（分钟），0 表示禁用
         */
        fun schedule(context: Context, intervalMinutes: Int) {
            val workManager = WorkManager.getInstance(context)
            
            if (intervalMinutes <= 0) {
                // 禁用自动更新，取消现有任务
                workManager.cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Cancelled global rule set auto-update")
                return
            }
            
            // 创建周期性工作请求
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<RuleSetAutoUpdateWorker>(
                intervalMinutes.toLong(),
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()
            
            // 使用 REPLACE 策略，如果已有相同名称的任务则替换
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled global rule set auto-update, interval: $intervalMinutes minutes")
        }
        
        /**
         * 取消全局规则集自动更新任务
         */
        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled global rule set auto-update")
        }
        
        /**
         * 根据已保存的设置重新调度自动更新任务
         * 在应用启动时调用
         */
        suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
            try {
                val settingsRepository = SettingsRepository.getInstance(context)
                val settings = settingsRepository.settings.first()
                
                if (settings.ruleSetAutoUpdateEnabled && settings.ruleSetAutoUpdateInterval > 0) {
                    schedule(context, settings.ruleSetAutoUpdateInterval)
                    Log.d(TAG, "Rescheduled global rule set auto-update with interval: ${settings.ruleSetAutoUpdateInterval} minutes")
                } else {
                    cancel(context)
                    Log.d(TAG, "Rule set auto-update is disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule auto-update task", e)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting global rule set auto-update")
        
        try {
            val settingsRepository = SettingsRepository.getInstance(applicationContext)
            val ruleSetRepository = RuleSetRepository.getInstance(applicationContext)
            
            // 检查是否仍然启用自动更新
            val settings = settingsRepository.settings.first()
            
            if (!settings.ruleSetAutoUpdateEnabled) {
                Log.d(TAG, "Auto-update disabled, cancelling work")
                cancel(applicationContext)
                return@withContext Result.success()
            }
            
            // 获取所有远程规则集并更新
            val remoteRuleSets = settings.ruleSets.filter { 
                it.type == RuleSetType.REMOTE && it.enabled 
            }
            
            if (remoteRuleSets.isEmpty()) {
                Log.d(TAG, "No remote rule sets to update")
                return@withContext Result.success()
            }
            
            Log.d(TAG, "Updating ${remoteRuleSets.size} remote rule sets")
            
            var successCount = 0
            var failCount = 0
            
            remoteRuleSets.forEach { ruleSet ->
                try {
                    val success = ruleSetRepository.prefetchRuleSet(
                        ruleSet = ruleSet, 
                        forceUpdate = true, 
                        allowNetwork = true
                    )
                    if (success) {
                        successCount++
                        Log.d(TAG, "Updated rule set: ${ruleSet.tag}")
                    } else {
                        failCount++
                        Log.w(TAG, "Failed to update rule set: ${ruleSet.tag}")
                    }
                } catch (e: Exception) {
                    failCount++
                    Log.e(TAG, "Error updating rule set: ${ruleSet.tag}", e)
                }
            }
            
            Log.d(TAG, "Auto-update completed: $successCount success, $failCount failed")
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update failed", e)
            
            // 如果失败，返回 retry 让 WorkManager 根据退避策略重试
            Result.retry()
        }
    }
}