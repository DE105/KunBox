package com.kunk.singbox.utils.parser

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig

/**
 * Sing-box JSON 格式解析器
 * 参考 NekoBox 的导入逻辑：只提取 outbounds 节点，忽略规则配置
 * 防止因 sing-box 规则版本更新导致解析失败
 */
class SingBoxParser(private val gson: Gson) : SubscriptionParser {
    companion object {
        private const val TAG = "SingBoxParser"
    }

    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    override fun parse(content: String): SingBoxConfig? {
        val trimmed = content.trim()

        // 如果是数组格式，直接解析为 outbounds 列表
        if (trimmed.startsWith("[")) {
            return parseAsOutboundArray(trimmed)
        }

        // 对象格式：只提取 outbounds 字段，忽略其他可能不兼容的字段
        return parseAsConfigObject(trimmed)
    }

    /**
     * 解析 JSON 数组格式（直接是 outbounds 列表）
     */
    private fun parseAsOutboundArray(content: String): SingBoxConfig? {
        return try {
            val outboundListType = object : TypeToken<List<Outbound>>() {}.type
            val outbounds: List<Outbound> = gson.fromJson(content, outboundListType)
            if (outbounds.isNotEmpty()) {
                SingBoxConfig(outbounds = outbounds)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse as outbound array: ${e.message}")
            null
        }
    }

    /**
     * 解析 JSON 对象格式，只提取 outbounds/proxies 字段
     */
    private fun parseAsConfigObject(content: String): SingBoxConfig? {
        return try {
            val jsonObject = JsonParser.parseString(content).asJsonObject

            // 优先尝试 outbounds 字段，其次 proxies
            val outboundsElement = jsonObject.get("outbounds") ?: jsonObject.get("proxies")

            if (outboundsElement != null && outboundsElement.isJsonArray) {
                val outboundListType = object : TypeToken<List<Outbound>>() {}.type
                val outbounds: List<Outbound> = gson.fromJson(outboundsElement, outboundListType)
                if (outbounds.isNotEmpty()) {
                    return SingBoxConfig(outbounds = outbounds)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract outbounds from JSON: ${e.message}")
            null
        }
    }
}

/**
 * Base64 订阅格式解析器 (V2Ray/Shadowrocket)
 */
class Base64Parser(private val nodeParser: (String) -> Outbound?) : SubscriptionParser {
    override fun canParse(content: String): Boolean {
        // 简单判断是否可能是 Base64 或包含节点链接
        val trimmed = content.trim()
        return !trimmed.startsWith("{") && !trimmed.startsWith("proxies:") && !trimmed.startsWith("proxy-groups:")
    }

    override fun parse(content: String): SingBoxConfig? {
        // 1. 尝试 Base64 解码
        val decoded = tryDecodeBase64(content.trim()) ?: content
        
        // 2. 按行分割
        val lines = decoded.lines().filter { it.isNotBlank() }
        val outbounds = mutableListOf<Outbound>()
        
        for (line in lines) {
            val trimmedLine = line.trim()
            // 3. 处理 Shadowrocket 的 remarks 和 plugin 参数 (如果存在)
            // 格式通常是: ss://...#remarks 或 ss://...?plugin=...
            // NodeLinkParser 已经处理了 #remarks (作为 tag)
            // 这里主要关注是否需要预处理一些非标准格式，目前 NodeLinkParser 应该足够健壮

            val outbound = nodeParser(trimmedLine)
            if (outbound != null) {
                outbounds.add(outbound)
            }
        }

        if (outbounds.isEmpty()) return null

        return SingBoxConfig(outbounds = outbounds)
    }

    private fun tryDecodeBase64(content: String): String? {
        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = Base64.decode(content, flags)
                val text = String(decoded)
                // 验证解码结果是否看起来像文本 (包含常见协议头或换行)
                if (text.isNotBlank() && (
                    text.contains("://") || 
                    text.contains("\n") || 
                    text.contains("\r") ||
                    text.all { it.isLetterOrDigit() || it.isWhitespace() || "=/-_:.".contains(it) }
                )) {
                    return text
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
