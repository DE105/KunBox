package com.kunk.singbox.model

import com.google.gson.annotations.SerializedName

data class GithubFile(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("size") val size: Long,
    @SerializedName("download_url") val download_url: String?
)