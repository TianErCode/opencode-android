package com.tianercode.opencode.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json






























@Serializable
data class AppUpdateManifest(
    @SerialName("schemaVersion") val schemaVersion: Int,
    
    @SerialName("openCodeVersion") val openCodeVersion: String,
    
    @SerialName("versionCode") val versionCode: Long,
    @SerialName("versionName") val versionName: String,
    @SerialName("apkUrl") val apkUrl: String,
    @SerialName("apkSha256") val apkSha256: String,
    @SerialName("apkSizeBytes") val apkSizeBytes: Long = 0,
    @SerialName("releaseNotes") val releaseNotes: String = "",
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported update manifest schema: $schemaVersion" }
        require(openCodeVersion.isNotBlank()) { "Update manifest has no OpenCode version" }
        require(versionCode > 0) { "Update manifest has no version code" }
        require(versionName.isNotBlank()) { "Update manifest has no version name" }
        
        
        
        require(apkUrl.startsWith("https://")) { "Update APK URL must use HTTPS: $apkUrl" }
        require(SHA256.matches(apkSha256)) { "Invalid update APK SHA-256: $apkSha256" }
    }

    companion object {
        private val SHA256 = Regex("^[a-f0-9]{64}$")

        val json: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        fun parse(payload: String): AppUpdateManifest = json.decodeFromString<AppUpdateManifest>(payload).also { it.validate() }
    }
}









sealed interface AppUpdateStatus {
    
    data object Disabled : AppUpdateStatus

    data object Idle : AppUpdateStatus

    data object Checking : AppUpdateStatus

    data class DownloadRequired(val version: String) : AppUpdateStatus

    data class Downloading(val version: String) : AppUpdateStatus

    data class Ready(val version: String) : AppUpdateStatus

    data object UpToDate : AppUpdateStatus

    data class Installing(val version: String) : AppUpdateStatus

    data class Error(val message: String) : AppUpdateStatus

    
    fun toJson(): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            when (this@AppUpdateStatus) {
                is Disabled -> put("status", kotlinx.serialization.json.JsonPrimitive("disabled"))
                is Idle -> put("status", kotlinx.serialization.json.JsonPrimitive("idle"))
                is Checking -> put("status", kotlinx.serialization.json.JsonPrimitive("checking"))
                is DownloadRequired -> {
                    put("status", kotlinx.serialization.json.JsonPrimitive("download-required"))
                    put("version", kotlinx.serialization.json.JsonPrimitive(version))
                }
                is Downloading -> {
                    put("status", kotlinx.serialization.json.JsonPrimitive("downloading"))
                    put("version", kotlinx.serialization.json.JsonPrimitive(version))
                }
                is Ready -> {
                    put("status", kotlinx.serialization.json.JsonPrimitive("ready"))
                    put("version", kotlinx.serialization.json.JsonPrimitive(version))
                }
                is UpToDate -> put("status", kotlinx.serialization.json.JsonPrimitive("up-to-date"))
                is Installing -> {
                    put("status", kotlinx.serialization.json.JsonPrimitive("installing"))
                    put("version", kotlinx.serialization.json.JsonPrimitive(version))
                }
                is Error -> {
                    put("status", kotlinx.serialization.json.JsonPrimitive("error"))
                    put("message", kotlinx.serialization.json.JsonPrimitive(message))
                }
            }
        }
}
