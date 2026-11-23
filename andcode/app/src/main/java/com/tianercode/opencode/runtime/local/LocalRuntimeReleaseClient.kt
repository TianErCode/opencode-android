package com.tianercode.opencode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class LocalRuntimeReleaseAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    val requiredFreeBytes: Long
        get() = sizeBytes * SPACE_MULTIPLIER + UPDATE_SAFETY_BYTES

    companion object {
        private const val SPACE_MULTIPLIER = 4L
        private const val UPDATE_SAFETY_BYTES = 64L * 1024L * 1024L
    }
}

data class LocalRuntimeRelease(
    val version: String,
    val releaseNotes: String,
    val asset: LocalRuntimeReleaseAsset,
)

sealed interface LocalRuntimeUpdateCheck {
    val currentVersion: String

    data class UpToDate(
        override val currentVersion: String,
        val latestVersion: String,
    ) : LocalRuntimeUpdateCheck

    data class Available(
        override val currentVersion: String,
        val release: LocalRuntimeRelease,
    ) : LocalRuntimeUpdateCheck
}














class LocalRuntimeReleaseClient(
    private val httpClient: OkHttpClient = downloadHttpClient(),
    private val endpoint: HttpUrl = OFFICIAL_RELEASE_ENDPOINT.toHttpUrl(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    init {
        require(endpoint.isHttps || endpoint.host in LOOPBACK_HOSTS) {
            "OpenCode release API must use HTTPS"
        }
    }

    suspend fun check(
        currentVersion: String,
        abi: String,
    ): LocalRuntimeUpdateCheck =
        withContext(Dispatchers.IO) {
            val assetName =
                requireNotNull(ASSET_NAME_BY_ABI[abi]) {
                    "Unsupported Android ABI for OpenCode updates: $abi"
                }
            val normalizedCurrent = normalizeRuntimeVersion(currentVersion)
            val request =
                Request.Builder()
                    .url(endpoint)
                    .header("Accept", JSON_ACCEPT)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            val channelDto =
                httpClient.newCall(request).execute().use { response ->
                    require(response.isSuccessful) {
                        "OpenCode release check failed with HTTP ${response.code}"
                    }
                    val body =
                        requireNotNull(response.body) {
                            "OpenCode release response had no body"
                        }
                    json.decodeFromString<ChannelDto>(body.string())
                }
            val artifact =
                requireNotNull(channelDto.artifacts.firstOrNull { it.distribution == ASSET_DISTRIBUTION }) {
                    "OpenCode channel does not publish a $ASSET_DISTRIBUTION artifact"
                }
            val latestVersion = normalizeRuntimeVersion(artifact.version)
            val fileDto =
                requireNotNull(artifact.metadata.files[assetName]) {
                    "OpenCode release $latestVersion does not contain $assetName"
                }
            require(SHA256_DIGEST.matches(fileDto.sha256)) {
                "OpenCode release asset is missing a valid SHA-256 digest"
            }
            val assetUrl = fileDto.url.toHttpUrl()
            require(assetUrl.isHttps) { "OpenCode release asset URL must use HTTPS" }
            require(fileDto.size > 0L) { "OpenCode release asset size must be positive" }

            val release =
                LocalRuntimeRelease(
                    version = latestVersion,
                    
                    
                    releaseNotes = "",
                    asset =
                        LocalRuntimeReleaseAsset(
                            name = assetName,
                            url = assetUrl.toString(),
                            sha256 = fileDto.sha256,
                            sizeBytes = fileDto.size,
                        ),
                )
            if (compareRuntimeVersions(latestVersion, normalizedCurrent) > 0) {
                LocalRuntimeUpdateCheck.Available(normalizedCurrent, release)
            } else {
                LocalRuntimeUpdateCheck.UpToDate(normalizedCurrent, latestVersion)
            }
        }

    @Serializable
    private data class ChannelDto(
        val artifacts: List<ArtifactDto> = emptyList(),
    )

    @Serializable
    private data class ArtifactDto(
        val distribution: String,
        val version: String,
        val metadata: ArtifactMetadataDto = ArtifactMetadataDto(),
    )

    @Serializable
    private data class ArtifactMetadataDto(
        val files: Map<String, FileDto> = emptyMap(),
    )

    @Serializable
    private data class FileDto(
        val url: String,
        val sha256: String,
        val size: Long,
    )

    companion object {
        const val OFFICIAL_RELEASE_ENDPOINT = "https://opencode.ai/update/api/latest/cli"
        private const val ASSET_DISTRIBUTION = "opencode"
        private const val JSON_ACCEPT = "application/json"
        private const val USER_AGENT = "OpenCode"
        private val SHA256_DIGEST = Regex("^[a-f0-9]{64}$")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        private val ASSET_NAME_BY_ABI =
            mapOf(
                "arm64-v8a" to "opencode-linux-arm64-musl.tar.gz",
                "x86_64" to "opencode-linux-x64-musl.tar.gz",
            )
    }
}

internal fun compareRuntimeVersions(
    left: String,
    right: String,
): Int {
    val leftParts = parseRuntimeVersion(left)
    val rightParts = parseRuntimeVersion(right)
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val comparison =
            (leftParts.getOrElse(index) { 0 })
                .compareTo(rightParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

internal fun normalizeRuntimeVersion(version: String): String = parseRuntimeVersion(version).joinToString(".")

private fun parseRuntimeVersion(version: String): List<Int> {
    val normalized =
        version.trim()
            .removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
    require(VERSION.matches(normalized)) { "Invalid OpenCode version: $version" }
    return normalized.split('.').map { component -> component.toInt() }
}

private val VERSION = Regex("^[0-9]+(?:\\.[0-9]+){1,3}$")