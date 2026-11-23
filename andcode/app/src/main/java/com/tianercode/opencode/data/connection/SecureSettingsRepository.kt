package com.tianercode.opencode.data.connection

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tianercode.opencode.runtime.local.CustomProviderDefinition
import com.tianercode.opencode.runtime.local.CustomProviderStore
import com.tianercode.opencode.runtime.local.LocalProviderCredentialStore














class SecureSettingsRepository(context: Context) {
    private val masterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private val preferences: SharedPreferences = createPreferences(context, masterKey)

    

    var providerApiKeys: Map<String, String>
        get() = providerApiKeys()
        set(value) {
            preferences.edit()
                .putString(KEY_PROVIDER_API_KEYS, LocalProviderCredentialStore.encodeMap(value))
                .apply()
        }

    fun providerApiKeys(): Map<String, String> =
        LocalProviderCredentialStore.decodeMap(preferences.getString(KEY_PROVIDER_API_KEYS, null))

    





    val hasManagedProviderApiKeyIds: Boolean
        get() = preferences.contains(KEY_MANAGED_PROVIDER_API_KEY_IDS)

    var managedProviderApiKeyIds: Set<String>
        get() =
            preferences.getStringSet(KEY_MANAGED_PROVIDER_API_KEY_IDS, emptySet())
                .orEmpty()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        set(value) {
            preferences.edit()
                .putStringSet(
                    KEY_MANAGED_PROVIDER_API_KEY_IDS,
                    value.map(String::trim).filter(String::isNotEmpty).toSet(),
                )
                .apply()
        }

    var customProviders: List<CustomProviderDefinition>
        get() = CustomProviderStore.decodeList(preferences.getString(KEY_CUSTOM_PROVIDERS, null))
        set(value) {
            preferences.edit()
                .putString(KEY_CUSTOM_PROVIDERS, CustomProviderStore.encodeList(value))
                .apply()
        }

    var syncedCustomProviderIds: Set<String>
        get() =
            preferences.getStringSet(KEY_SYNCED_CUSTOM_PROVIDER_IDS, emptySet())
                .orEmpty()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        set(value) {
            preferences.edit()
                .putStringSet(
                    KEY_SYNCED_CUSTOM_PROVIDER_IDS,
                    value.map(String::trim).filter(String::isNotEmpty).toSet(),
                )
                .apply()
        }

    

    





    var defaultServerUrl: String?
        get() = preferences.getString(KEY_DEFAULT_SERVER_URL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            preferences.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_DEFAULT_SERVER_URL) else putString(KEY_DEFAULT_SERVER_URL, value)
            }.apply()
        }

    





    var localRuntimePinchZoomEnabled: Boolean
        get() = preferences.getBoolean(KEY_PINCH_ZOOM_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_PINCH_ZOOM_ENABLED, value).apply()

    

    





    var localRuntimeIdleStopEnabled: Boolean
        get() = preferences.getBoolean(KEY_LOCAL_RUNTIME_IDLE_STOP_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_LOCAL_RUNTIME_IDLE_STOP_ENABLED, value).apply()

    




    var localRuntimeStoppedByUser: Boolean
        get() = preferences.getBoolean(KEY_LOCAL_RUNTIME_STOPPED_BY_USER, false)
        set(value) = preferences.edit().putBoolean(KEY_LOCAL_RUNTIME_STOPPED_BY_USER, value).apply()

    private companion object {
        private fun createPreferences(
            context: Context,
            masterKey: MasterKey,
        ): SharedPreferences =
            runCatching { buildPreferences(context, masterKey) }
                
                
                .recoverCatching {
                    context.deleteSharedPreferences(PREFS_NAME)
                    buildPreferences(context, masterKey)
                }.getOrThrow()

        private fun buildPreferences(
            context: Context,
            masterKey: MasterKey,
        ): SharedPreferences =
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

        private const val PREFS_NAME = "opencode_secure_settings"

        private const val KEY_PROVIDER_API_KEYS = "provider_api_keys"
        private const val KEY_MANAGED_PROVIDER_API_KEY_IDS = "managed_provider_api_key_ids"
        private const val KEY_CUSTOM_PROVIDERS = "custom_providers"
        private const val KEY_SYNCED_CUSTOM_PROVIDER_IDS = "synced_custom_provider_ids"
        private const val KEY_LOCAL_RUNTIME_IDLE_STOP_ENABLED = "local_runtime_idle_stop_enabled"
        private const val KEY_LOCAL_RUNTIME_STOPPED_BY_USER = "local_runtime_stopped_by_user"
        private const val KEY_DEFAULT_SERVER_URL = "default_server_url"
        private const val KEY_PINCH_ZOOM_ENABLED = "local_runtime_pinch_zoom_enabled"
    }
}
