package com.kunk.singbox.model

import com.google.gson.annotations.SerializedName

// 过滤模式枚举
enum class FilterMode {
    @SerializedName("NONE") NONE,      // 不过滤
    @SerializedName("INCLUDE") INCLUDE,   // 只显示包含关键字的节点
    @SerializedName("EXCLUDE") EXCLUDE    // 排除包含关键字的节点
}

// 节点过滤配置数据类
data class NodeFilter(
    @SerializedName("keywords") val keywords: List<String> = emptyList(),
    @SerializedName("filterMode") val filterMode: FilterMode = FilterMode.NONE
)