package com.tianercode.opencode.runtime.local

import com.tianercode.opencode.data.connection.SecureSettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption


@Serializable
data class CustomProviderDefinition(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>,
)

internal fun CustomProviderDefinition.normalized(): CustomProviderDefinition =
    copy(
        id = id.trim(),
        name = name.trim().ifEmpty { id.trim() },
        baseUrl = baseUrl.trim(),
        models = models.map(String::trim).filter(String::isNotEmpty).distinct(),
    )










class CustomProviderStore(
    private val load: () -> List<CustomProviderDefinition>,
    private val save: (List<CustomProviderDefinition>) -> Unit,
    private val loadSyncedIds: () -> Set<String> = { emptySet() },
    private val saveSyncedIds: (Set<String>) -> Unit = {},
    private val json: Json = defaultJson,
) {
    constructor(settings: SecureSettingsRepository, json: Json = defaultJson) : this(
        load = { settings.customProviders },
        save = { settings.customProviders = it },
        loadSyncedIds = { settings.syncedCustomProviderIds },
        saveSyncedIds = { settings.syncedCustomProviderIds = it },
        json = json,
    )

    fun definitions(): List<CustomProviderDefinition> = load()

    fun upsert(definition: CustomProviderDefinition) {
        val normalized = definition.normalized()
        require(normalized.id.isNotEmpty()) { "Provider id is required" }
        require(normalized.name.isNotEmpty()) { "Provider name is required" }
        require(normalized.baseUrl.isNotEmpty()) { "Base URL is required" }
        require(normalized.models.isNotEmpty()) { "At least one model id is required" }
        save(definitions().filterNot { it.id == normalized.id } + normalized)
    }

    fun remove(id: String) {
        val normalizedId = id.trim()
        save(definitions().filterNot { it.id == normalizedId })
    }

    




    fun syncToRuntime(rootfs: File): File {
        val configFile = File(rootfs, "root/.config/opencode/opencode.json")
        val existingRoot = readExistingPayload(configFile)
        val providers = (existingRoot["provider"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()

        val current = definitions()
        val currentIds = current.map { it.id }.toSet()
        (loadSyncedIds() - currentIds).forEach(providers::remove)
        current.forEach { definition -> providers[definition.id] = definition.toProviderConfig() }

        val updatedRoot = JsonObject(existingRoot + ("provider" to JsonObject(providers)))
        if (updatedRoot != existingRoot) {
            writeAtomically(configFile, json.encodeToString(updatedRoot))
        }
        saveSyncedIds(currentIds)
        return configFile
    }

    private fun readExistingPayload(configFile: File): JsonObject {
        if (!configFile.isFile) return JsonObject(emptyMap())
        return runCatching {
            json.parseToJsonElement(configFile.readText()).jsonObject
        }.getOrElse { error ->
            throw IllegalStateException(
                "Existing OpenCode opencode.json is invalid and was not modified",
                error,
            )
        }
    }

    private fun writeAtomically(
        destination: File,
        content: String,
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.delete()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            destination.setReadable(true, true)
            destination.setWritable(true, true)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

        fun encodeList(
            definitions: List<CustomProviderDefinition>,
            json: Json = defaultJson,
        ): String = json.encodeToString(definitions)

        fun decodeList(
            raw: String?,
            json: Json = defaultJson,
        ): List<CustomProviderDefinition> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<CustomProviderDefinition>>(raw) }.getOrDefault(emptyList())
        }
    }
}


internal fun CustomProviderDefinition.toProviderConfig(): JsonObject =
    buildJsonObject {
        put("npm", "@ai-sdk/openai-compatible")
        put("name", name)
        put("options", buildJsonObject { put("baseURL", baseUrl) })
        put(
            "models",
            buildJsonObject {
                models.forEach { modelId -> put(modelId, buildJsonObject { put("name", modelId) }) }
            },
        )
    }
