package com.kunbox.singbox.utils

import android.util.Log
import java.util.Locale

/**
 * 在线获取“常见代理应用”包名列表。
 * 约束：不做本地或内存缓存，每次调用都实时拉取。
 */
object ProxyPackageListFetcher {
    private const val TAG = "ProxyPkgFetcher"
    private const val PROXY_LIST_URL =
        "https://raw.githubusercontent.com/2dust/androidpackagenamelist/master/proxy.txt"

    data class PackageMatcher(val exactPackages: Set<String>, val prefixPackages: Set<String>) {
        fun isEmpty(): Boolean = exactPackages.isEmpty() && prefixPackages.isEmpty()

        fun matches(packageName: String): Boolean {
            val normalized = packageName.trim().lowercase(Locale.US)
            if (normalized in exactPackages) return true
            return prefixPackages.any { prefix ->
                normalized == prefix || normalized.startsWith("$prefix.")
            }
        }
    }

    suspend fun fetchMatcherNoCache(): PackageMatcher {
        return try {
            val result = KernelHttpClient.smartFetch(
                url = PROXY_LIST_URL,
                preferKernel = true,
                timeoutMs = 8000,
            )
            if (!result.isOk) {
                Log.w(TAG, "Fetch failed: ${result.error ?: "HTTP ${result.statusCode}"}")
                return PackageMatcher(emptySet(), emptySet())
            }
            val exactPackages = parsePackages(result.body)
            val prefixPackages = buildPrefixPackages(exactPackages)
            PackageMatcher(exactPackages, prefixPackages)
        } catch (e: Exception) {
            Log.w(TAG, "Fetch exception: ${e.message}")
            PackageMatcher(emptySet(), emptySet())
        }
    }

    private fun parsePackages(content: String): Set<String> {
        if (content.isBlank()) return emptySet()
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.lowercase(Locale.US) }
            .filter { pkg -> pkg.count { it == '.' } >= 1 && !pkg.contains(' ') }
            .toCollection(LinkedHashSet())
    }

    /**
     * 从在线精确包名自动推导“家族前缀”：
     * - 4 段及以上前缀：>= 2 个包出现即可
     * - 3 段前缀：>= 3 个包出现
     * - 2 段前缀：>= 8 个包出现（避免过度泛化）
     */
    private fun buildPrefixPackages(exactPackages: Set<String>): Set<String> {
        val prefixCount = mutableMapOf<String, Int>()

        exactPackages.forEach { pkg ->
            val parts = pkg.split('.')
            if (parts.size < 3) return@forEach
            for (depth in 2 until parts.size) {
                val prefix = parts.take(depth).joinToString(".")
                prefixCount[prefix] = (prefixCount[prefix] ?: 0) + 1
            }
        }

        return prefixCount.asSequence()
            .filter { (prefix, count) ->
                val depth = prefix.count { it == '.' } + 1
                when {
                    depth >= 4 -> count >= 2
                    depth == 3 -> count >= 3
                    else -> count >= 8
                }
            }
            .map { it.key }
            .toCollection(LinkedHashSet())
    }
}
