package com.tianercode.opencode.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tianercode.opencode.runtime.local.downloadHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest














class AppUpdateInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = downloadHttpClient(),
) {
    private val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }

    
    fun cachedFile(): File? = updateDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".apk") }

    





    suspend fun download(manifest: AppUpdateManifest): File =
        withContext(Dispatchers.IO) {
            val target = File(updateDir, "OpenCode-Android-update.apk")
            val part = File(updateDir, "OpenCode-Android-update.apk.part")
            updateDir.listFiles()?.forEach { it.delete() }

            try {
                val request = Request.Builder().url(manifest.apkUrl).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("下载更新失败：HTTP ${response.code}")
                    val body = response.body ?: error("下载更新失败：响应为空")
                    val declaredLength = body.contentLength()
                    if (manifest.apkSizeBytes > 0 && declaredLength > 0 && declaredLength != manifest.apkSizeBytes) {
                        error("更新包大小不一致：清单 ${manifest.apkSizeBytes}，服务器 $declaredLength")
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                received += read
                                if (manifest.apkSizeBytes > 0 && received > manifest.apkSizeBytes) {
                                    error("更新包大小超过清单：${manifest.apkSizeBytes}")
                                }
                            }
                        }
                    }

                    if (manifest.apkSizeBytes > 0 && received != manifest.apkSizeBytes) {
                        error("更新包不完整：期望 ${manifest.apkSizeBytes}，收到 $received")
                    }
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(manifest.apkSha256, ignoreCase = true)) {
                        error("更新包 SHA-256 校验失败")
                    }
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    target
                }
            } catch (failure: Throwable) {
                runCatching { part.delete() }
                throw failure
            }
        }

    






    fun launchInstaller(apk: File): Result<Unit> =
        runCatching {
            require(apk.isFile && apk.length() > 0) { "更新包已不存在，请重新检查更新" }

            if (!context.packageManager.canRequestPackageInstalls()) {
                runCatching {
                    val settings =
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settings)
                }
                error("请先允许安装未知应用，然后再点一次安装")
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", apk)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
}
