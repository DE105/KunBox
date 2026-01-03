package com.kunk.singbox.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 订阅自动更新 Worker
 * 使用 WorkManager 在后台定期更新订阅
 */
class SubscriptionAutoUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SubscriptionAutoUpdate"
        private const val WORK_NAME_PREFIX = "subscription_auto_update_"
        
        /**
         * 调度订阅自动更新任务
         * @param context Context
         * @param profileId 配置 ID
         * @param intervalMinutes 更新间隔（分钟），0 表示禁用
         */
        fun schedule(context: Context, profileId: String, intervalMinutes: Int) {
            val workManager = WorkManager.getInstance(context)
            val workName = "$WORK_NAME_PREFIX$profileId"
            
            if (intervalMinutes <= 0) {
                // 禁用自动更新，取消现有任务
                workManager.cancelUniqueWork(workName)
                Log.d(TAG, "Cancelled auto-update for profile: $profileId")
                return
            }
            
            // 创建周期性工作请求
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val inputData = Data.Builder()
                .putString("profile_id", profileId)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<SubscriptionAutoUpdateWorker>(
                intervalMinutes.toLong(),
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()
            
            // 使用 REPLACE 策略，如果已有相同名称的任务则替换
            workManager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled auto-update for profile: $profileId, interval: $intervalMinutes minutes")
        }
        
        /**
         * 取消订阅自动更新任务
         */
        fun cancel(context: Context, profileId: String) {
            val workManager = WorkManager.getInstance(context)
            val workName = "$WORK_NAME_PREFIX$profileId"
            workManager.cancelUniqueWork(workName)
            Log.d(TAG, "Cancelled auto-update for profile: $profileId")
        }
        
        /**
         * 取消所有订阅自动更新任务
         */
        fun cancelAll(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelAllWorkByTag(TAG)
            Log.d(TAG, "Cancelled all auto-update tasks")
        }
        
        /**
         * 根据已保存的配置重新调度所有自动更新任务
         * 在应用启动时调用
         */
        suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
            try {
                val configRepository = ConfigRepository.getInstance(context)
                val profiles = configRepository.profiles.first()
                
                profiles.forEach { profile ->
                    if (profile.type == ProfileType.Subscription && 
                        profile.enabled && 
                        profile.autoUpdateInterval > 0) {
                        schedule(context, profile.id, profile.autoUpdateInterval)
                    }
                }
                
                Log.d(TAG, "Rescheduled auto-update for ${profiles.count { it.autoUpdateInterval > 0 }} profiles")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule auto-update tasks", e)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val profileId = inputData.getString("profile_id")
        
        if (profileId.isNullOrBlank()) {
            Log.e(TAG, "Profile ID is missing")
            return@withContext Result.failure()
        }
        
        Log.d(TAG, "Starting auto-update for profile: $profileId")
        
        try {
            val configRepository = ConfigRepository.getInstance(applicationContext)
            
            // 检查配置是否仍然存在且启用
            val profile = configRepository.profiles.first().find { it.id == profileId }
            if (profile == null) {
                Log.w(TAG, "Profile not found: $profileId, cancelling auto-update")
                cancel(applicationContext, profileId)
                return@withContext Result.failure()
            }
            
            if (!profile.enabled) {
                Log.d(TAG, "Profile disabled: $profileId, skipping update")
                return@withContext Result.success()
            }
            
            if (profile.autoUpdateInterval <= 0) {
                Log.d(TAG, "Auto-update disabled for profile: $profileId, cancelling work")
                cancel(applicationContext, profileId)
                return@withContext Result.success()
            }
            
            // 执行更新
            val result = configRepository.updateProfile(profileId)
            
            Log.d(TAG, "Auto-update completed for profile: $profileId, result: $result")
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update failed for profile: $profileId", e)
            
            // 如果失败，返回 retry 让 WorkManager 根据退避策略重试
            Result.retry()
        }
    }
}