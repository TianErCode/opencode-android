package com.tianercode.opencode.bridge

import android.content.Context
import com.tianercode.opencode.OpenCodeApplication
import com.tianercode.opencode.R
import com.tianercode.opencode.core.storage.DeviceStorageAccess
import com.tianercode.opencode.runtime.LocalRuntimeStatus
import com.tianercode.opencode.runtime.local.LocalRuntimeManifestReader
import com.tianercode.opencode.runtime.local.LocalRuntimeServiceCredential
import com.tianercode.opencode.runtime.local.LocalRuntimeUpdateCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject













class RuntimeBridge(
    private val context: Context,
    private val emit: (JsonObject) -> Unit,
    
    private val host: OpenCodeBridge.Host,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val app: OpenCodeApplication get() = context.applicationContext as OpenCodeApplication

    
    @Volatile
    private var update: LocalRuntimeUpdateCheck? = null

    init {
        scope.launch {
            app.localRuntimeManager.state.collect { emit(snapshot()) }
        }
    }

    fun snapshot(): JsonObject {
        val status = app.localRuntimeManager.status()
        val bundled = runCatching { LocalRuntimeManifestReader(context).read().openCodeVersion }.getOrNull().orEmpty()
        val check = update

        return buildJsonObject {
            put("state", state(status))
            put("job", jobFor(status))
            
            
            
            
            
            put("storageGranted", JsonPrimitive(DeviceStorageAccess(context).isGranted()))
            put("bundledVersion", JsonPrimitive(bundled))
            put("updateAvailable", JsonPrimitive(check is LocalRuntimeUpdateCheck.Available))
            put("latestVersion", JsonPrimitive(latestVersion(check)))
            put("freeBytes", JsonPrimitive(app.filesDir.usableSpace))
        }
    }

    private fun state(status: LocalRuntimeStatus): JsonObject =
        when (status) {
            is LocalRuntimeStatus.NotInstalled -> buildJsonObject { put("kind", JsonPrimitive("not-installed")) }
            
            
            
            is LocalRuntimeStatus.AwaitingStoragePermission ->
                buildJsonObject {
                    put("kind", JsonPrimitive("awaiting-permission"))
                    put("step", JsonPrimitive(context.getString(R.string.install_step_needing_storage_permission)))
                }
            is LocalRuntimeStatus.Installing ->
                buildJsonObject {
                    put("kind", JsonPrimitive("installing"))
                    put("progress", status.progress?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                    put("step", JsonPrimitive(status.step))
                }
            is LocalRuntimeStatus.Starting ->
                buildJsonObject {
                    put("kind", JsonPrimitive("starting"))
                    put("version", JsonPrimitive(status.version))
                    put("port", JsonPrimitive(status.port))
                }
            is LocalRuntimeStatus.Updating ->
                buildJsonObject {
                    put("kind", JsonPrimitive("updating"))
                    put("progress", status.progress?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                    put("step", JsonPrimitive(status.step))
                }
            is LocalRuntimeStatus.Ready ->
                buildJsonObject {
                    put("kind", JsonPrimitive("ready"))
                    put("version", JsonPrimitive(status.version))
                    put("port", JsonPrimitive(status.port))
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    put("url", JsonPrimitive("http://127.0.0.1:${status.port}"))
                    put(
                        "password",
                        LocalRuntimeServiceCredential.password(context)?.let { JsonPrimitive(it) }
                            ?: JsonPrimitive(null as String?),
                    )
                }
            is LocalRuntimeStatus.Stopped ->
                buildJsonObject {
                    put("kind", JsonPrimitive("stopped"))
                    put("version", JsonPrimitive(status.version))
                    put("port", JsonPrimitive(status.port))
                }
            is LocalRuntimeStatus.Broken ->
                buildJsonObject {
                    put("kind", JsonPrimitive("failed"))
                    put("message", JsonPrimitive(status.reason))
                }
            is LocalRuntimeStatus.UnsupportedAbi ->
                buildJsonObject {
                    put("kind", JsonPrimitive("failed"))
                    put("message", JsonPrimitive("Unsupported CPU: ${status.abi}"))
                }
        }

    
    private fun jobFor(status: LocalRuntimeStatus): JsonPrimitive =
        when (status) {
            is LocalRuntimeStatus.Installing -> JsonPrimitive("install")
            is LocalRuntimeStatus.Starting -> JsonPrimitive("start")
            is LocalRuntimeStatus.Updating -> JsonPrimitive("update")
            else -> JsonPrimitive(null as String?)
        }

    private fun latestVersion(check: LocalRuntimeUpdateCheck?): String? =
        when (check) {
            null -> null
            is LocalRuntimeUpdateCheck.UpToDate -> check.latestVersion
            is LocalRuntimeUpdateCheck.Available -> check.release.version
        }

    

    fun install() = app.localRuntimeController.installAndStart()

    fun start() = app.localRuntimeController.start()

    










    fun requestStorageAccess(): Boolean = host.openDeviceStorageSettings()

    fun stop() = app.localRuntimeController.stop()

    fun update() = app.localRuntimeController.update()

    fun rollback() = app.localRuntimeController.rollback()

    





    fun reinstall() = app.localRuntimeController.reinstall()

    fun delete() = app.localRuntimeController.delete()

    
    fun retry() {
        if (app.hasInstalledRuntime()) start() else install()
    }

    





    fun checkUpdate() {
        scope.launch {
            
            
            if (app.localRuntimeManager.status() !is LocalRuntimeStatus.Ready) return@launch
            update = app.localRuntimeManager.checkForUpdate().getOrNull()
            emit(snapshot())
        }
    }

    





    fun close() {
        runCatching { scope.cancel() }
    }
}
