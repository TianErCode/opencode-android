package com.tianercode.opencode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit








data class ReachableSource(
    val url: String,
    val totalBytes: Long?,
    val bytesPerSecond: Double,
    val millisToFirstByte: Long,
    










    val sampleMillis: Long,
    
    val rejection: String? = null,
) {
    val usable: Boolean get() = rejection == null
}
























class RuntimeSourceProbe(
    private val httpClient: OkHttpClient = defaultClient(),
) {
    







    suspend fun rank(
        candidates: List<String>,
        sampleBytes: Long = DEFAULT_SAMPLE_BYTES,
        











        samples: Int = 1,
        



        order: Comparator<ReachableSource> = BULK_ORDER,
        onEachResult: (ReachableSource) -> Unit = {},
    ): List<ReachableSource> =
        withContext(Dispatchers.IO) {
            val distinct = candidates.distinct()
            if (distinct.size <= 1) {
                
                return@withContext distinct.map { unprobed(it) }
            }
            
            
            
            
            runCatching {
                coroutineScope {
                    distinct.map { url ->
                        async { probeRepeatedly(url, sampleBytes, samples).also(onEachResult) }
                    }.awaitAll()
                }
            }.getOrElse { error ->
                android.util.Log.w(LOG_TAG, "Source ranking failed, falling back to the given order", error)
                return@withContext distinct.map { unprobed(it) }
            }.sortedWith(order)
        }

    






    private suspend fun probeRepeatedly(
        url: String,
        sampleBytes: Long,
        samples: Int,
    ): ReachableSource {
        val rounds =
            (1..samples.coerceAtLeast(1)).map {
                kotlinx.coroutines.withContext(Dispatchers.IO) { probe(url, sampleBytes) }
            }
        val reachable = rounds.filter { it.usable }
        if (reachable.isEmpty()) return rounds.first()
        val ordered = reachable.sortedBy { it.sampleMillis }
        val median = ordered[ordered.size / 2]
        
        
        return median
    }

    
    private fun unprobed(
        url: String,
    ): ReachableSource =
        ReachableSource(url = url, totalBytes = null, bytesPerSecond = 0.0, millisToFirstByte = 0L, sampleMillis = 0L)

    








    private fun probe(
        url: String,
        sampleBytes: Long,
    ): ReachableSource =
        runCatching { probeOnce(url, sampleBytes) }.getOrElse { error ->
            ReachableSource(
                url = url,
                totalBytes = null,
                bytesPerSecond = 0.0,
                millisToFirstByte = 0L,
                sampleMillis = Long.MAX_VALUE,
                rejection = "unreachable (${error.javaClass.simpleName})",
            )
        }

    private fun probeOnce(
        url: String,
        sampleBytes: Long,
    ): ReachableSource {
        val startedAt = System.nanoTime()
        var firstByteAt = 0L
        var moved = 0L
        val request =
            Request.Builder()
                .url(url)
                .get()
                
                
                .header("Range", "bytes=0-${sampleBytes - 1}")
                .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != HTTP_PARTIAL_CONTENT) {
                return ReachableSource(
                    url = url,
                    totalBytes = null,
                    bytesPerSecond = 0.0,
                    millisToFirstByte = 0L,
                    sampleMillis = Long.MAX_VALUE,
                    
                    
                    
                    
                    
                    rejection =
                        when (response.code) {
                            HTTP_NOT_FOUND -> "HTTP 404, does not carry this version"
                            HTTP_FORBIDDEN -> "HTTP 403, does not carry this version"
                            else -> "HTTP ${response.code}"
                        },
                )
            }
            val total =
                response.header("Content-Range")
                    ?.substringAfter('/', "")
                    ?.trim()
                    ?.toLongOrNull()
                    ?: response.body?.contentLength()?.takeIf { it > 0L && response.code != HTTP_PARTIAL_CONTENT }
            val body = response.body
            if (body != null) {
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (moved < sampleBytes) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (firstByteAt == 0L) firstByteAt = System.nanoTime()
                        moved += count
                    }
                }
            }
            val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
            return ReachableSource(
                url = url,
                totalBytes = total,
                
                
                bytesPerSecond = if (elapsedSeconds > 0.0) moved / elapsedSeconds else 0.0,
                millisToFirstByte = if (firstByteAt == 0L) 0L else (firstByteAt - startedAt) / 1_000_000L,
                sampleMillis = ((System.nanoTime() - startedAt) / 1_000_000L),
            )
        }
    }

    companion object {
        private const val DEFAULT_SAMPLE_BYTES = 3L * 1024 * 1024
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_FORBIDDEN = 403

        




        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .ipv4First()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

        private const val LOG_TAG = "AndCode.Runtime"

        



        val BULK_ORDER: Comparator<ReachableSource> =
            compareByDescending<ReachableSource> { it.usable }
                
                
                .thenByDescending { if (it.totalBytes != null) 1 else 0 }
                .thenByDescending { it.bytesPerSecond }
                .thenBy { it.millisToFirstByte }

        



        val SMALL_REQUEST_ORDER: Comparator<ReachableSource> =
            compareByDescending<ReachableSource> { it.usable }
                .thenBy { if (it.usable) it.sampleMillis else Long.MAX_VALUE }
                .thenByDescending { it.bytesPerSecond }
    }
}