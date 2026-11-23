package com.tianercode.opencode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException












data class DownloadProgress(
    val fraction: Float?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
) {
    companion object {
        val NONE = DownloadProgress(null, 0L, null, null)
    }
}


private class HttpStatusException(val code: Int) : IOException("Runtime download failed with HTTP $code")





private class RestartTransfer : IOException("Runtime download must restart from zero")


private class AllSourcesExhausted(val tried: List<String>, cause: Throwable) :
    IOException("Runtime download failed from all ${tried.size} sources: ${tried.joinToString()}", cause)

class VerifiedRuntimeDownloader(
    private val httpClient: OkHttpClient = downloadHttpClient(),
    







    private val maxAttemptsPerSource: Int = 6,
) {
    private val operationMutex = Mutex()

    




    suspend fun download(
        urls: List<String>,
        destination: File,
        expectedSha256: String,
        expectedSizeBytes: Long? = null,
        headers: Map<String, String> = emptyMap(),
        onProgress: (DownloadProgress) -> Unit = {},
        



        onRetry: (attempt: Int, waitMillis: Long, switchingSource: Boolean) -> Unit = { _, _, _ -> },
        



        onSourceSwitch: (from: String, to: String) -> Unit = { _, _ -> },
    ) {
        require(urls.isNotEmpty()) { "Runtime download needs at least one source" }
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                downloadLocked(
                    urls = urls,
                    destination = destination,
                    expectedSha256 = expectedSha256,
                    expectedSizeBytes = expectedSizeBytes,
                    headers = headers,
                    onProgress = onProgress,
                    onRetry = onRetry,
                    onSourceSwitch = onSourceSwitch,
                )
            }
        }
    }

    private suspend fun downloadLocked(
        urls: List<String>,
        destination: File,
        expectedSha256: String,
        expectedSizeBytes: Long?,
        headers: Map<String, String>,
        onProgress: (DownloadProgress) -> Unit,
        onRetry: (attempt: Int, waitMillis: Long, switchingSource: Boolean) -> Unit,
        onSourceSwitch: (from: String, to: String) -> Unit,
    ) {
        val parsed =
            urls.distinct().map { url ->
                val parsedUrl = url.toHttpUrl()
                require(parsedUrl.isHttps || parsedUrl.host in LOOPBACK_HOSTS) {
                    "Runtime download URL must use HTTPS"
                }
                parsedUrl
            }
        require(SHA256.matches(expectedSha256)) { "Invalid expected SHA-256" }
        require(expectedSizeBytes == null || expectedSizeBytes > 0L) {
            "Expected download size must be positive"
        }
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".partial")
        val backup = File(destination.parentFile, destination.name + ".backup")
        recoverBackupIfDestinationMissing(destination, backup)

        var completed = false
        var validationFailed = false
        try {
            var reportedTotal: Long? = expectedSizeBytes
            if (expectedSizeBytes != null && partial.length() > expectedSizeBytes) {
                partial.delete()
            }
            
            
            
            val partialAlreadyComplete =
                partial.length() > 0L &&
                    (expectedSizeBytes == null || partial.length() == expectedSizeBytes) &&
                    runCatching {
                        RuntimeArchive.verifySha256(partial, expectedSha256)
                    }.isSuccess
            if (!partialAlreadyComplete && expectedSizeBytes != null && partial.length() == expectedSizeBytes) {
                partial.delete()
            }
            if (!partialAlreadyComplete) {
                val downloadStartedAt = System.nanoTime()
                var movedThisRun = 0L
                var lastError: Throwable? = null
                outer@ for ((index, url) in parsed.withIndex()) {
                    if (index > 0) onSourceSwitch(parsed[index - 1].toString(), url.toString())
                    var attempt = 0
                    while (true) {
                        
                        
                        val attemptStartedAt = downloadStartedAt
                        val movedBeforeAttempt = movedThisRun
                        try {
                            movedThisRun +=
                                transferOnce(
                                    parsedUrl = url,
                                    partial = partial,
                                    headers = headers,
                                    startedAt = attemptStartedAt,
                                    alreadyMoved = movedBeforeAttempt,
                                    onReport = { downloaded, total, moved ->
                                        reportedTotal = total
                                        onProgress(
                                            progressOf(
                                                downloaded = downloaded,
                                                total = total,
                                                moved = movedBeforeAttempt + moved,
                                                startedAt = attemptStartedAt,
                                            ),
                                        )
                                    },
                                )
                            break@outer
                        } catch (error: Throwable) {
                            if (error is RestartTransfer) {
                                
                                
                                movedThisRun = 0L
                                continue
                            }
                            if (!isRetryable(error)) {
                                
                                
                                partial.delete()
                                lastError = error
                                continue@outer
                            }
                            if (attempt >= maxAttemptsPerSource) {
                                lastError = error
                                continue@outer
                            }
                            attempt++
                            val waitMillis = backoffMillis(attempt)
                            
                            
                            
                            
                            
                            
                            android.util.Log.w(
                                LOG_TAG,
                                "download attempt $attempt/$maxAttemptsPerSource for ${url.host}${url.encodedPath} " +
                                    "failed after $movedThisRun bytes: ${error.javaClass.simpleName}: ${error.message}",
                                error,
                            )
                            onRetry(attempt, waitMillis, false)
                            delay(waitMillis)
                        }
                    }
                }
                
                
                
                if (lastError != null) {
                    if (expectedSizeBytes != null && partial.length() < expectedSizeBytes) {
                        
                    } else {
                        partial.delete()
                    }
                    throw AllSourcesExhausted(parsed.map { it.toString() }, lastError)
                }
                validationFailed = true
                RuntimeArchive.verifySha256(partial, expectedSha256)
                validationFailed = false
            }

            if (destination.isFile &&
                runCatching {
                    RuntimeArchive.verifySha256(destination, expectedSha256)
                }.isSuccess
            ) {
                partial.delete()
                backup.delete()
                val landed = partial.length()
                onProgress(DownloadProgress(1f, landed, reportedTotal ?: landed, null))
                return
            }

            if (!backup.exists() && destination.exists()) {
                move(destination, backup)
            } else if (backup.exists() && destination.exists()) {
                require(destination.delete()) {
                    "Unable to remove the unverified runtime download"
                }
            }
            try {
                move(partial, destination)
                backup.delete()
                completed = true
            } catch (error: Throwable) {
                destination.delete()
                if (backup.exists()) {
                    runCatching { move(backup, destination) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                }
                throw error
            }
            val landed = destination.length()
            onProgress(DownloadProgress(1f, landed, reportedTotal ?: landed, null))
        } catch (error: Throwable) {
            
            
            if (validationFailed) partial.delete()
            throw error
        } finally {
            if (completed) partial.delete()
            recoverBackupIfDestinationMissing(destination, backup)
        }
    }

    




    private fun transferOnce(
        parsedUrl: HttpUrl,
        partial: File,
        headers: Map<String, String>,
        startedAt: Long,
        alreadyMoved: Long,
        onReport: (downloaded: Long, total: Long?, moved: Long) -> Unit,
    ): Long {
        var downloaded = partial.length().coerceAtLeast(0L)
        val resumeOffset = downloaded
        val resume = resumeOffset > 0L
        val requestBuilder = Request.Builder().url(parsedUrl).get()
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        if (resume) requestBuilder.header("Range", "bytes=$resumeOffset-")
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            
            
            
            if (resume && response.code == 416) {
                require(partial.delete()) { "Unable to discard an invalid partial download" }
                throw RestartTransfer()
            }
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = requireNotNull(response.body) { "Runtime download response had no body" }
            
            
            val append = resume && response.code == 206
            
            
            
            
            
            
            
            
            val remaining = body.contentLength().takeIf { it > 0L }
            val total = remaining?.let { if (append) resumeOffset + it else it }
            if (append) {
                require(response.header("Content-Range")?.startsWith("bytes $resumeOffset-") == true) {
                    "Runtime download returned an invalid Content-Range"
                }
            }
            if (!append) downloaded = 0L
            FileOutputStream(partial, append).buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        
                        
                        
                        
                        onReport(downloaded, total, downloaded - resumeOffset)
                    }
                }
            }
            return downloaded - resumeOffset
        }
    }

    






    private fun isRetryable(error: Throwable): Boolean =
        when (error) {
            is HttpStatusException -> error.code >= 500 || error.code == 408 || error.code == 429
            
            
            is IOException -> true
            else -> false
        }

    
    private fun backoffMillis(attempt: Int): Long =
        minOf(MAX_BACKOFF_MILLIS, 500L shl (attempt - 1).coerceIn(0, 16))

    







    private fun progressOf(
        downloaded: Long,
        total: Long?,
        moved: Long,
        startedAt: Long,
    ): DownloadProgress {
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        
        val rate = if (seconds >= 0.1 && moved > 0) (moved / seconds).toLong() else null
        return DownloadProgress(
            fraction = total?.takeIf { it > 0 }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) },
            downloadedBytes = downloaded,
            totalBytes = total,
            bytesPerSecond = rate,
        )
    }

    private fun recoverBackupIfDestinationMissing(
        destination: File,
        backup: File,
    ) {
        if (!destination.exists() && backup.exists()) {
            move(backup, destination)
        }
    }

    private fun move(
        source: File,
        destination: File,
    ) {
        require(source.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
            "Verified runtime download moves must stay on one filesystem"
        }
        destination.parentFile?.mkdirs()
        require(source.renameTo(destination)) {
            "Unable to move ${source.name} to ${destination.name}"
        }
    }

    companion object {
        
        private const val LOG_TAG = "AndCode.Runtime"
        private val SHA256 = Regex("^[a-f0-9]{64}$")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        private const val MAX_BACKOFF_MILLIS = 8_000L
    }
}