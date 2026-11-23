package com.tianercode.opencode.bridge

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import com.tianercode.opencode.BuildConfig
import com.tianercode.opencode.OpenCodeApplication
import com.tianercode.opencode.core.diagnostics.CrashLog
import com.tianercode.opencode.runtime.LocalRuntimeStatus
import com.tianercode.opencode.update.AppUpdateChecker
import com.tianercode.opencode.update.AppUpdateInstaller
import com.tianercode.opencode.update.AppUpdateStatus
import com.tianercode.opencode.runtime.local.LocalRuntimeManifestReader
import com.tianercode.opencode.runtime.local.LocalRuntimeServiceCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


















class OpenCodeBridge(
    private val context: Context,
    private val transport: BridgeTransport,
    private val host: Host,
    













    private val hostOrigin: () -> String? = { null },
) {
    







    private val eventLock = Any()

    
    private val queuedEvents = mutableListOf<Outbound>()

    
    @Volatile
    private var pageReady = false

    






    private sealed interface Outbound {
        data class Resolve(
            val ticket: String,
            val ok: Boolean,
            val payloadJson: String,
        ) : Outbound

        data class Event(
            val channel: String,
            val payloadJson: String,
        ) : Outbound
    }

    
    private val runtime = RuntimeBridge(context, { snapshot -> emit("runtime", snapshot) }, host)

    










    @Volatile
    private var updateStatus: AppUpdateStatus =
        if (AppUpdateChecker.isConfigured(BuildConfig.UPDATE_MANIFEST_URL)) {
            AppUpdateStatus.Idle
        } else {
            AppUpdateStatus.Disabled
        }

    private val appUpdateChecker = AppUpdateChecker(context)
    private val appUpdateInstaller = AppUpdateInstaller(context)

    
    @Volatile
    private var updateDownloading: Boolean = false

    private fun emitUpdater() {
        runCatching { emit("updater", updateStatus.toJson()) }
    }
    
    interface Host {
        
        fun pickDirectories(
            multiple: Boolean,
            onResult: (List<String>) -> Unit,
        )

        fun pickFiles(
            multiple: Boolean,
            extensions: List<String>?,
            onResult: (List<File>) -> Unit,
        )

        fun relaunch()

        fun stopEngine()

        fun applyBackgroundColor(argb: Int)

        fun applyZoom(factor: Float)

        
        fun reloadPage()

        
        fun setFullscreen(enabled: Boolean)

        







        fun runScript(script: String)

        fun isWindowFocused(): Boolean

        




        fun reportRoute(path: String) {}

        






        fun openDeviceStorageSettings(): Boolean
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val storeRoot = File(context.filesDir, "web-store").apply { mkdirs() }
    private val draftRoot = File(context.filesDir, "web-drafts").apply { mkdirs() }
    private val blobRoot = File(context.filesDir, "web-blobs").apply { mkdirs() }

    
    private val deferred = ConcurrentHashMap<String, (JsonElement?) -> Unit>()

    
    private val pickedFiles = ConcurrentHashMap<String, List<File>>()

    
    @Volatile
    private var zoomFactor: Float = 1f

    
    @Volatile
    private var fullscreen: Boolean = false

    private val app: OpenCodeApplication get() = context.applicationContext as OpenCodeApplication

    

    








    fun dispatchWithTicket(
        ticket: String,
        method: String,
        argsJson: String,
    ) {
        runDispatch(ticket, method, argsJson)
    }

    private fun runDispatch(
        ticket: String,
        method: String,
        argsJson: String,
    ) {
        val args =
            runCatching {
                json.parseToJsonElement(if (argsJson.isBlank()) "[]" else argsJson) as JsonArray
            }.getOrElse { JsonArray(emptyList()) }
        onFirstDispatch()

        scope.launch {
            val reply =
                runCatching { handle(method, args, ticket) }
                    .fold(
                        onSuccess = { it },
                        onFailure = { Reply.Failure(it.message ?: it::class.java.simpleName) },
                    )
            when (reply) {
                is Reply.Immediate -> resolve(ticket, true, reply.payload?.toString() ?: "null")
                is Reply.Failure ->
                    resolve(ticket, false, JsonPrimitive(reply.message).toString())
                
                Reply.Held -> Unit
            }
        }
    }

    private fun resolve(
        ticket: String,
        ok: Boolean,
        payload: String,
    ) {
        
        
        deliver(Outbound.Resolve(ticket, ok, payload))
    }

    





    private fun emit(
        channel: String,
        payload: JsonElement?,
    ) {
        
        
        
        
        deliver(Outbound.Event(channel, payload?.toString() ?: "null"))
    }

    






    private fun deliver(message: Outbound) {
        synchronized(eventLock) {
            if (!pageReady) {
                queuedEvents.add(message)
                return
            }
        }
        send(message)
    }

    private fun send(message: Outbound) {
        when (message) {
            is Outbound.Resolve -> transport.resolve(message.ticket, message.ok, message.payloadJson)
            is Outbound.Event -> transport.emit(message.channel, message.payloadJson)
        }
    }

    




    






    fun close() {
        runCatching { scope.cancel() }
        runCatching { runtime.close() }
        synchronized(eventLock) {
            queuedEvents.clear()
            pageReady = false
        }
    }
    private fun onFirstDispatch() {
        val queued =
            synchronized(eventLock) {
                if (pageReady) return
                pageReady = true
                queuedEvents.toList().also { queuedEvents.clear() }
            }
        queued.forEach(::send)
    }

    private fun settle(
        ticket: String,
        payload: JsonElement?,
    ) {
        val handler = deferred.remove(ticket) ?: return
        handler(payload)
        resolve(ticket, true, payload?.toString() ?: "null")
    }

    

    private fun handle(
        method: String,
        args: JsonArray,
        ticket: String,
    ): Reply {
        
        
        
        
        
        if (com.tianercode.opencode.BuildConfig.DEBUG) {
            android.util.Log.d("AndCode.Bridge", "rpc $method")
        }
        return when (method) {
            
            "awaitInitialization" -> Reply.Immediate(awaitInitialization())
            
            
            
            "sidecarAuthorization" -> Reply.Immediate(sidecarAuthorization())
            "getWindowID" -> Reply.Immediate(JsonPrimitive(windowId()))
            "consumeInitialDeepLinks" -> Reply.Immediate(JsonArray(emptyList()))
            "getWindowFocused" -> Reply.Immediate(JsonPrimitive(host.isWindowFocused()))
            "getWindowFullscreen" -> Reply.Immediate(JsonPrimitive(fullscreen))
            "showWindow", "setWindowFocus" -> Reply.Immediate(null)

            
            
            "AppReportRoute" -> {
                host.reportRoute(args.str(0).orEmpty())
                Reply.Immediate(JsonPrimitive(true))
            }
            "relaunch" -> Reply.Immediate(host.relaunch().let { null })
            "killSidecar" -> Reply.Immediate(host.stopEngine().let { null })

            
            "storeGet" -> Reply.Immediate(JsonPrimitive(store(args.str(0)).read(args.str(1))))
            "storeSet" -> Reply.Immediate(store(args.str(0)).write(args.str(1), args.str(2)).let { null })
            "storeDelete" -> Reply.Immediate(store(args.str(0)).delete(args.str(1)).let { null })
            "storeClear" -> Reply.Immediate(store(args.str(0)).clear().let { null })
            "storeKeys" ->
                Reply.Immediate(
                    buildJsonArray { store(args.str(0)).keys().forEach { add(JsonPrimitive(it)) } },
                )
            "storeLength" -> Reply.Immediate(JsonPrimitive(store(args.str(0)).keys().size))
            "draftGet" -> Reply.Immediate(JsonPrimitive(readDraft(args.str(0))))
            "draftSet" -> Reply.Immediate(writeDraft(args.str(0), args.str(1)).let { null })
            "draftDelete" -> Reply.Immediate(draftFile(args.str(0)).delete().let { null })
            "draftBlobPut" -> Reply.Immediate(JsonPrimitive(putBlob(args.str(0))))
            "draftBlobGet" -> Reply.Immediate(JsonPrimitive(getBlob(args.str(0))))

            
            "openDirectoryPicker" -> {
                val multiple = args.obj(0)?.flag("multiple") ?: false
                deferred[ticket] = { payload ->
                    val paths = (payload as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }.orEmpty()
                    
                    if (multiple) JsonArray(paths.map(::JsonPrimitive)) else JsonPrimitive(paths.firstOrNull())
                }
                host.pickDirectories(multiple) { settle(ticket, JsonArray(it.map(::JsonPrimitive))) }
                return Reply.Held
            }
            "openFilePicker" -> {
                val multiple = args.obj(0)?.flag("multiple") ?: false
                val extensions = args.obj(0)?.get("extensions")?.let { it as? JsonArray }?.mapNotNull { it.contentOrNullSafe() }
                val token = UUID.randomUUID().toString()
                deferred[ticket] = { settlePicker(token, it) }
                host.pickFiles(multiple, extensions) { files ->
                    pickedFiles[token] = files
                    settle(
                        ticket,
                        buildJsonObject {
                            put("token", JsonPrimitive(token))
                            put(
                                "files",
                                buildJsonArray {
                                    files.forEach { file ->
                                        add(
                                            buildJsonObject {
                                                put("path", JsonPrimitive(file.absolutePath))
                                                put("name", JsonPrimitive(file.name))
                                                put("size", JsonPrimitive(file.length()))
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
                return Reply.Held
            }
            "readPickedFile" -> Reply.Immediate(JsonPrimitive(readPicked(args.str(0), args.str(1))))
            "releasePickedFiles" -> Reply.Immediate(pickedFiles.remove(args.str(0)).let { null })
            "saveFilePicker" -> Reply.Immediate(null)
            "readClipboardImage" -> Reply.Immediate(readClipboardImage())

            
            "openExternal" -> Reply.Immediate(openExternal(args.str(0)).let { null })
            "openLocalFile", "openPath" -> Reply.Immediate(null)
            "checkAppExists" -> Reply.Immediate(JsonPrimitive(false))
            "resolveAppPath" -> Reply.Immediate(null)
            "installCli" -> Reply.Failure("Not available on Android")

            
            "getZoomFactor" -> Reply.Immediate(JsonPrimitive(zoomFactor))
            "setZoomFactor" ->
                Reply.Immediate(
                    run {
                        setZoom(args.num(0))
                        null
                    },
                )
            "getPinchZoomEnabled" -> Reply.Immediate(JsonPrimitive(app.settings.localRuntimePinchZoomEnabled))
            "setPinchZoomEnabled" ->
                Reply.Immediate(
                    run {
                        app.settings.localRuntimePinchZoomEnabled = args.flag(0)
                        null
                    },
                )
            "runDesktopMenuAction" -> Reply.Immediate(runDesktopMenuAction(args.str(0)).let { null })

            
            "setBackgroundColor" ->
                Reply.Immediate(
                    args.str(0)?.let { host.applyBackgroundColor(parseColor(it)) }.let { null },
                )
            "setForceFocus" -> Reply.Immediate(null)
            "getDisplayBackend" -> Reply.Immediate(null)
            "setTitlebar" -> Reply.Immediate(null)

            
            
            
            
            
            
            
            
            
            
            "updaterState" ->
                Reply.Immediate(updateStatus.toJson())

            "updaterCheck" -> {
                
                
                
                
                scope.launch {
                    updateStatus = AppUpdateStatus.Checking
                    emitUpdater()
                    val result = appUpdateChecker.check()
                    updateStatus = result
                    emitUpdater()
                    resolve(ticket, true, result.toJson().toString())
                }
                Reply.Held
            }

            
            
            
            
            
            "updaterInstall" -> {
                when (val current = updateStatus) {
                    is AppUpdateStatus.Ready -> {
                        val apk = appUpdateInstaller.cachedFile()
                        if (apk == null) {
                            Reply.Failure("更新包已不存在，请重新检查更新")
                        } else {
                            val installed =
                                appUpdateInstaller.launchInstaller(apk).fold(
                                    onSuccess = { Reply.Immediate(null) },
                                    onFailure = { Reply.Failure(it.message ?: "无法启动系统安装器") },
                                )
                            installed
                        }
                    }

                    is AppUpdateStatus.DownloadRequired -> {
                        val manifest = appUpdateChecker.lastManifest
                        if (manifest == null) {
                            Reply.Failure("没有可下载的更新清单，请重新检查更新")
                        } else if (manifest.versionCode <= appUpdateChecker.currentVersionCode()) {
                            Reply.Failure("清单里的版本不比当前版本新，请重新检查更新")
                        } else if (updateDownloading) {
                            Reply.Failure("更新正在下载")
                        } else {
                            updateDownloading = true
                            updateStatus = AppUpdateStatus.Downloading(current.version)
                            emitUpdater()
                            scope.launch {
                                val result = runCatching { appUpdateInstaller.download(manifest) }
                                updateDownloading = false
                                result.fold(
                                    onSuccess = {
                                        updateStatus = AppUpdateStatus.Ready(manifest.versionName)
                                        emitUpdater()
                                        resolve(ticket, true, "null")
                                    },
                                    onFailure = { failure ->
                                        val message = failure.message ?: "下载更新失败"
                                        updateStatus = AppUpdateStatus.Error(message)
                                        emitUpdater()
                                        resolve(ticket, false, JsonPrimitive(message).toString())
                                    },
                                )
                            }
                            Reply.Held
                        }
                    }

                    else -> Reply.Failure("没有可安装的更新，请先检查更新")
                }
            }

            
            "exportDebugLogs" -> Reply.Immediate(JsonPrimitive(readDiagnostics()))
            "recordFatalRendererError" -> Reply.Immediate(recordFatal(args.obj(0)).let { null })

            
            
            
            
            "debugRunScript" ->
                if (!BuildConfig.DEBUG) {
                    Reply.Failure("debugRunScript is not available in a release build")
                } else {
                    host.runScript(args.str(0) ?: "")
                    Reply.Immediate(null)
                }

            
            "getDefaultServerUrl" -> Reply.Immediate(JsonPrimitive(app.settings.defaultServerUrl))
            "setDefaultServerUrl" ->
                Reply.Immediate(
                    run {
                        app.settings.defaultServerUrl = args.str(0)
                        null
                    },
                )
            "isFirstLaunchOnboardingPending" -> Reply.Immediate(JsonPrimitive(false))
            "finishFirstLaunchOnboarding" -> Reply.Immediate(null)
            "isOldLayoutEligible" -> Reply.Immediate(JsonPrimitive(false))

            
            "runtime.getState" -> Reply.Immediate(runtime.snapshot())
            "runtime.install" -> Reply.Immediate(runtime.install().let { null })
            "runtime.start" -> Reply.Immediate(runtime.start().let { null })
            "runtime.requestStorageAccess" -> Reply.Immediate(JsonPrimitive(runtime.requestStorageAccess()))
            "runtime.stop" -> Reply.Immediate(runtime.stop().let { null })
            "runtime.update" -> Reply.Immediate(runtime.update().let { null })
            "runtime.rollback" -> Reply.Immediate(runtime.rollback().let { null })
            "runtime.reinstall" -> Reply.Immediate(runtime.reinstall().let { null })
            "runtime.delete" -> Reply.Immediate(runtime.delete().let { null })
            "runtime.retry" -> Reply.Immediate(runtime.retry().let { null })
            "runtime.checkUpdate" -> Reply.Immediate(runtime.checkUpdate().let { null })

            
            
            
            "unsupported" -> Reply.Failure("Not available on Android")

            else -> Reply.Failure("Unknown bridge method: $method")
        }
    }

    private fun settlePicker(
        token: String,
        payload: JsonElement?,
    ): JsonElement = payload ?: JsonNull

    

    


























    private fun awaitInitialization(): JsonElement =
        buildJsonObject {
            
            
            
            
            
            
            
            
            
            
            
            put("url", JsonPrimitive(hostOrigin() ?: sidecarOrigin()))
        }

    













































    private fun sidecarAuthorization(): JsonElement {
        val password = LocalRuntimeServiceCredential.password(context) ?: return JsonNull
        val token = Base64.encodeToString(
            "opencode:$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        return JsonPrimitive("Basic $token")
    }

    
    private fun sidecarOrigin(): String {
        val status = app.localRuntimeManager.status()
        val port =
            when (status) {
                is LocalRuntimeStatus.Ready -> status.port
                else -> runCatching { LocalRuntimeManifestReader(context).read().port }.getOrDefault(DEFAULT_PORT)
            }
        return "http://127.0.0.1:$port"
    }

    
    private fun windowId(): String {
        val file = File(context.filesDir, "web-window-id")
        if (!file.isFile) file.writeText(UUID.randomUUID().toString())
        return file.readText().ifBlank { UUID.randomUUID().toString() }
    }

    

    private fun store(name: String?) = KeyValueStore(File(storeRoot, sanitize(name.orEmpty())))

    private fun draftFile(key: String?) = File(draftRoot, sanitize(key.orEmpty()))

    private fun readDraft(key: String?): String? = draftFile(key).takeIf { it.isFile }?.readText()

    private fun writeDraft(
        key: String?,
        value: String?,
    ) {
        if (key == null) return
        draftFile(key).writeText(value.orEmpty())
    }

    





    private fun putBlob(base64: String?): String? {
        if (base64 == null) return null
        val id = UUID.randomUUID().toString()
        File(blobRoot, id).writeBytes(Base64.decode(base64, Base64.DEFAULT))
        return id
    }

    private fun getBlob(id: String?): String? {
        if (id == null) return null
        val file = File(blobRoot, sanitize(id))
        if (!file.isFile) return null
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    

    private fun readPicked(
        token: String?,
        path: String?,
    ): String? {
        if (token == null || path == null) return null
        val file = pickedFiles[token]?.firstOrNull { it.absolutePath == path } ?: return null
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    private fun readClipboardImage(): JsonElement? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val uri =
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.uri
                ?: return null
        val bitmap =
            runCatching {
                context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return buildJsonObject {
            put("buffer", JsonPrimitive(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)))
            put("width", JsonPrimitive(bitmap.width))
            put("height", JsonPrimitive(bitmap.height))
        }
    }

    

    private fun openExternal(url: String?) {
        if (url == null) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
            )
        }
    }

    























    private fun runDesktopMenuAction(action: String?) {
        when (action) {
            "view.reload" -> host.reloadPage()
            
            
            
            
            
            
            
            "view.toggleFullscreen", "view.fullscreen" -> {
                fullscreen = !fullscreen
                host.setFullscreen(fullscreen)
            }
        }
    }

    






    private fun setZoom(factor: Float) {
        zoomFactor = factor.coerceIn(MIN_ZOOM, MAX_ZOOM)
        host.applyZoom(zoomFactor)
    }

    private fun parseColor(value: String?): Int =
        runCatching { Color.parseColor(value?.trim().orEmpty()) }.getOrDefault(Color.WHITE)

    private fun recordFatal(error: JsonObject?) {
        val message = error?.get("error")?.jsonPrimitive?.contentOrNullSafe() ?: "unknown"
        val url = error?.get("url")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        Log.e(TAG, "renderer: $message ($url)")
    }

    private fun readDiagnostics(): String =
        buildString {
            appendLine("opencode-android renderer diagnostics")
            appendLine("handled bridge members: ${HANDLED.size}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine(CrashLog.read(context) ?: "no crash log recorded")
        }

    

    private fun JsonArray.str(index: Int): String? = getOrNull(index)?.contentOrNullSafe()

    private fun JsonArray.num(index: Int): Float =
        getOrNull(index)?.jsonPrimitive?.floatOrNull ?: 1f

    private fun JsonArray.flag(index: Int): Boolean =
        getOrNull(index)?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonArray.obj(index: Int): JsonObject? = getOrNull(index) as? JsonObject

    private fun JsonElement.contentOrNullSafe(): String? =
        if (this is JsonNull) null else runCatching { jsonPrimitive.content }.getOrNull()

    private fun JsonObject.flag(key: String): Boolean =
        runCatching { get(key)?.jsonPrimitive?.booleanOrNull ?: false }.getOrDefault(false)

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private sealed interface Reply {
        data class Immediate(
            val payload: JsonElement?,
        ) : Reply

        
        data object Held : Reply

        data class Failure(
            val message: String,
        ) : Reply
    }

    private companion object {
        const val TAG = "OpenCodeBridge"

        const val DEFAULT_PORT = 4097
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 10f

        



        val HANDLED =
            setOf(
                "awaitInitialization", "sidecarAuthorization",
                "getWindowID", "consumeInitialDeepLinks",
                "getWindowFocused", "getWindowFullscreen", "showWindow", "setWindowFocus",
                "relaunch", "killSidecar",
                "storeGet", "storeSet", "storeDelete", "storeClear", "storeKeys", "storeLength",
                "draftGet", "draftSet", "draftDelete", "draftBlobPut", "draftBlobGet",
                "openDirectoryPicker", "openFilePicker", "readPickedFile", "releasePickedFiles",
                "saveFilePicker", "readClipboardImage",
                "openExternal", "openLocalFile", "openPath", "checkAppExists", "resolveAppPath",
                "installCli",
                "getZoomFactor", "setZoomFactor", "getPinchZoomEnabled", "setPinchZoomEnabled",
                "runDesktopMenuAction",
                "setBackgroundColor", "setForceFocus", "getDisplayBackend", "setTitlebar",
                "updaterState", "updaterCheck", "updaterInstall",
                "exportDebugLogs", "recordFatalRendererError",
                "getDefaultServerUrl", "setDefaultServerUrl",
                "isFirstLaunchOnboardingPending", "finishFirstLaunchOnboarding", "isOldLayoutEligible",
            )
    }
}


private class KeyValueStore(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun readAll(): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            val obj = json.parseToJsonElement(file.readText()) as JsonObject
            obj.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun writeAll(map: Map<String, String>) {
        file.parentFile?.mkdirs()
        file.writeText(buildJsonObject { map.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString())
    }

    fun keys(): List<String> = readAll().keys.sorted()

    fun read(key: String?): String? = key?.let { readAll()[it] }

    fun write(
        key: String?,
        value: String?,
    ) {
        if (key == null) return
        readAll().apply { this[key] = value.orEmpty() }.let(::writeAll)
    }

    fun delete(key: String?) {
        if (key == null) return
        readAll().apply { remove(key) }.let(::writeAll)
    }

    fun clear() {
        file.delete()
    }
}

