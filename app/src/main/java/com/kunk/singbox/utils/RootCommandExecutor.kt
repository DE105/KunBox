package com.kunk.singbox.utils

import android.content.Context
import android.util.Log
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import java.io.File
import java.util.concurrent.TimeUnit

data class RootCommandResult(
    val attempted: Boolean,
    val success: Boolean,
    val exitCode: Int? = null,
    val output: String = "",
    val timedOut: Boolean = false
)

object RootCommandExecutor {
    private const val TAG = "RootCommandExecutor"
    private const val ROOT_TIMEOUT_MS = 8_000L
    private const val MAX_OUTPUT_LENGTH = 400
    private const val PER_USER_RANGE = 100_000

    @Volatile
    private var cachedSuAvailable: Boolean? = null

    fun hasSuBinary(): Boolean {
        cachedSuAvailable?.let { return it }

        val knownPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system_ext/bin/su"
        )
        val existsInPath = knownPaths.any { path -> File(path).exists() }
        if (existsInPath) {
            cachedSuAvailable = true
            return true
        }

        val checkResult = runCommand(listOf("sh", "-c", "command -v su"), timeoutMs = 2_000L)
        val available = checkResult.success && checkResult.output.isNotBlank()
        cachedSuAvailable = available
        return available
    }

    fun requestRootAccess(): RootCommandResult {
        val result = runCommand(listOf("su", "-c", "id"), ROOT_TIMEOUT_MS)
        val granted = result.success && result.output.contains("uid=0")
        return result.copy(success = granted)
    }

    fun startServiceWithRoot(context: Context, tunMode: Boolean): RootCommandResult {
        val packageName = context.packageName
        val className = if (tunMode) {
            SingBoxService::class.java.name
        } else {
            ProxyOnlyService::class.java.name
        }
        val component = "$packageName/$className"
        val action = SingBoxService.ACTION_START
        val currentUserId = resolveCurrentUserId()
        val targetUserIds = linkedSetOf(currentUserId, 0)
        val commands = mutableListOf<String>()
        targetUserIds.forEach { userId ->
            commands += "am start-foreground-service --user $userId -n $component -a $action"
            commands += "am startservice --user $userId -n $component -a $action"
        }
        commands += "am start-foreground-service -n $component -a $action"
        commands += "am startservice -n $component -a $action"

        var lastResult = RootCommandResult(attempted = true, success = false, output = "not executed")
        for (command in commands) {
            val result = runCommand(listOf("su", "-c", command), ROOT_TIMEOUT_MS)
            lastResult = result
            if (result.success) {
                return result
            }
            Log.w(TAG, "Root start command failed: cmd=$command, exit=${result.exitCode}, out=${result.output}")
        }
        return lastResult
    }

    private fun runCommand(command: List<String>, timeoutMs: Long): RootCommandResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (process.isAlive) {
                    process.destroyForcibly()
                }
                return RootCommandResult(
                    attempted = true,
                    success = false,
                    timedOut = true,
                    output = "command timeout"
                )
            }

            val rawOutput = process.inputStream.bufferedReader().use { it.readText().trim() }
            val output = if (rawOutput.length > MAX_OUTPUT_LENGTH) {
                rawOutput.take(MAX_OUTPUT_LENGTH)
            } else {
                rawOutput
            }
            val exitCode = process.exitValue()
            val success = exitCode == 0 &&
                !output.contains("Error:", ignoreCase = true) &&
                !output.contains("SecurityException", ignoreCase = true) &&
                !output.contains("permission denied", ignoreCase = true) &&
                !output.contains("not allowed", ignoreCase = true)

            RootCommandResult(
                attempted = true,
                success = success,
                exitCode = exitCode,
                output = output
            )
        } catch (e: Exception) {
            RootCommandResult(
                attempted = true,
                success = false,
                output = e.message ?: "unknown error"
            )
        }
    }

    private fun resolveCurrentUserId(): Int {
        val uid = android.os.Process.myUid()
        return (uid / PER_USER_RANGE).coerceAtLeast(0)
    }
}
