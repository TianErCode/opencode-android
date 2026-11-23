package com.tianercode.opencode.update

import android.content.Context
import com.tianercode.opencode.BuildConfig
import com.tianercode.opencode.runtime.local.downloadHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl



















class AppUpdateChecker(
    private val context: Context,
    private val endpoint: String = BuildConfig.UPDATE_MANIFEST_URL,
    private val httpClient: OkHttpClient = downloadHttpClient(),
) {
    




    @Volatile
    var lastManifest: AppUpdateManifest? = null
        private set

    
    fun currentVersionCode(): Long =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .let { @Suppress("DEPRECATION") it.versionCode.toLong() }
        }.getOrDefault(0L)

    private fun currentVersionName(): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .let { @Suppress("DEPRECATION") it.versionName }
        }.getOrNull().orEmpty()

    







    suspend fun check(): AppUpdateStatus =
        withContext(Dispatchers.IO) {
            if (endpoint.isBlank()) return@withContext AppUpdateStatus.Disabled
            val url =
                runCatching { endpoint.toHttpUrl() }.getOrElse {
                    return@withContext AppUpdateStatus.Error("更新地址无效")
                }
            runCatching {
                val request = Request.Builder().url(url).header("Accept", JSON_ACCEPT).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AppUpdateStatus.Error("更新检查失败：HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    val manifest = AppUpdateManifest.parse(body)
                    lastManifest = manifest
                    compare(manifest)
                }
            }.getOrElse { error ->
                AppUpdateStatus.Error(error.message ?: "更新检查失败")
            }
        }

    






    private fun compare(manifest: AppUpdateManifest): AppUpdateStatus {
        val current = currentVersionCode()
        if (manifest.versionCode <= current) return AppUpdateStatus.UpToDate
        return AppUpdateStatus.DownloadRequired(manifest.versionName)
    }


    companion object {
        private const val JSON_ACCEPT = "application/json"

        
        fun isConfigured(endpoint: String): Boolean =
            endpoint.isNotBlank() && runCatching { endpoint.toHttpUrl() }.isSuccess

    }
}
