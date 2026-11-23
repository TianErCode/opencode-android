package com.tianercode.opencode.runtime.local

import android.content.Context
import com.tianercode.opencode.R




























internal class RouteALegacyNetworking(
    private val context: Context,
    private val abi: String,
    
    private val sourceProbe: RuntimeSourceProbe,
    
    private val alpineVersion: () -> String,
    
    private val apkMirrors: List<String>,
    
    private val apkRepositories: List<String>,
) {
    




    @Volatile
    private var lastProbeNote: ReachableSource? = null

    
    private fun probeStepText(
        total: Int,
        seen: Int,
    ): String {
        val step = context.getString(R.string.install_step_choosing_source, seen, total)
        val note = lastProbeNote
        if (note == null || !note.usable) return step
        return "$step  ·  ${String.format(java.util.Locale.ROOT, "%.1f", note.bytesPerSecond / (1024.0 * 1024.0))} MB/s"
    }

    






















    internal suspend fun rankedApkMirrors(
        onProgress: (Float?, String) -> Unit,
    ): List<String> {
        val branch = alpineBranch()
        var seen = 0
        fun report() = onProgress(null, probeStepText(apkMirrors.size, seen))

        
        
        
        
        
        
        
        
        
        val mainRanked =
            sourceProbe.rank(
                apkRepositoryLines(apkMirrors, repository = "main").map { apkIndexUrl(it) },
                APK_PROBE_SAMPLE_BYTES,
                samples = APK_PROBE_SAMPLES,
                order = RuntimeSourceProbe.SMALL_REQUEST_ORDER,
            ) { probed ->
                seen++
                lastProbeNote = probed
                report()
            }
        val usableBases =
            mainRanked.filter { it.usable }.map { apkMirrorBaseOf(it.url) }.distinct()
        val everyBase = mainRanked.map { apkMirrorBaseOf(it.url) }.distinct()
        
        
        
        
        
        val bestRate = mainRanked.firstOrNull { it.usable }?.bytesPerSecond ?: 0.0
        val limit =
            if (bestRate >= SLOW_SOURCE_BYTES_PER_SECOND) APK_MIRROR_LIMIT_FAST_SOURCE else APK_MIRROR_LIMIT_FAST_SOURCE + 1
        val chosen = if (usableBases.isEmpty()) everyBase else usableBases.take(limit)

        
        
        
        
        val communityRanked =
            sourceProbe.rank(
                apkRepositoryLines(chosen, repository = "community").map { apkIndexUrl(it) },
                APK_PROBE_SAMPLE_BYTES,
                samples = APK_PROBE_SAMPLES,
                order = RuntimeSourceProbe.SMALL_REQUEST_ORDER,
            ) { probed ->
                lastProbeNote = probed
                report()
            }
        val withCommunity = chosen.filter { base -> communityRanked.any { it.usable && apkMirrorBaseOf(it.url) == base } }
        val final = if (withCommunity.isEmpty()) chosen else withCommunity

        
        
        
        android.util.Log.i(
            LOG_TAG,
            "apk mirrors: chose ${final.size} of ${apkMirrors.size} usableMain=${usableBases.size} " +
                "withCommunity=${withCommunity.size}; main ranked by one-request latency=" +
                mainRanked.joinToString(" | ") { probed ->
                    "${apkMirrorBaseOf(probed.url)} -> " +
                        (
                            probed.rejection
                                ?: "${probed.sampleMillis}ms " +
                                    String.format(java.util.Locale.ROOT, "%.2f MB/s", probed.bytesPerSecond / (1024.0 * 1024.0))
                        )
                },
        )
        android.util.Log.i(LOG_TAG, "apk repositories file will list: $final")
        return final
    }

    









    private fun apkRepositoryLines(
        mirrors: List<String>,
        repository: String = apkRepositories.joinToString(","),
    ): List<String> {
        val branch = alpineBranch()
        return mirrors.flatMap { base -> repository.split(",").map { "$base/$branch/$it" } }
    }

    
    private fun apkIndexUrl(
        repositoryLine: String,
    ): String = "$repositoryLine/${apkArchitecture()}/APKINDEX.tar.gz"

    private fun apkMirrorBaseOf(
        url: String,
    ): String = apkMirrors.first { base -> url.startsWith("$base/") }

    












    private fun alpineBranch(): String {
        val version = alpineVersion().trimStart('v')
        val branch = "v" + version.substringBeforeLast('.', version)
        require(BRANCH_PATTERN.matches(branch)) {
            "Alpine repository branch must be vMAJOR.MINOR, got '$branch' from version '$version'"
        }
        return branch
    }

    
    private fun apkArchitecture(): String =
        when (abi) {
            "arm64-v8a" -> "aarch64"
            else -> "x86_64"
        }

    private companion object {
        
        private const val LOG_TAG = "AndCode.Runtime"

        
        private val BRANCH_PATTERN = Regex("^v\\d+\\.\\d+$")

        







        private const val APK_PROBE_SAMPLE_BYTES = 64L * 1024

        








        private const val APK_PROBE_SAMPLES = 3

        










        private const val APK_MIRROR_LIMIT_FAST_SOURCE = 1

        
        private const val SLOW_SOURCE_BYTES_PER_SECOND = 2L * 1024 * 1024
    }
}
