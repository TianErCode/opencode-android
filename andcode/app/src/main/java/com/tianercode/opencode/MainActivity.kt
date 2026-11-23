package com.tianercode.opencode

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.Toast
import java.io.ByteArrayInputStream
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tianercode.opencode.bridge.OpenCodeBridge
import com.tianercode.opencode.runtime.LocalRuntimeStatus
import com.tianercode.opencode.runtime.local.LocalRuntimeServiceCredential
import com.tianercode.opencode.runtime.local.streamingHttpClient
import com.tianercode.opencode.runtime.local.LocalRuntimeManifestReader
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import com.tianercode.opencode.runtime.local.LocalRuntimeProcessLauncher
import org.json.JSONObject
import com.tianercode.opencode.core.storage.DeviceStorage
import com.tianercode.opencode.core.storage.DeviceStorageAccess
import com.tianercode.opencode.startup.RuntimeAutoStartInitializer
import com.tianercode.opencode.startup.RuntimeAutoStartTrigger
import com.tianercode.opencode.startup.shouldRestoreOnForegroundReturn
import com.tianercode.opencode.ui.RescueTerminalView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect









class MainActivity : ComponentActivity(), OpenCodeBridge.Host {
    
    private var geckoRuntime: GeckoRuntime? = null

    private var geckoSession: GeckoSession? = null

    





    private var canGoBackInPage = false

    
    private var pageAtHome = true

    
    private var lastKnownPageUrl = ""

    
    private var lastReportedPath = ""

    







    private var exitArmed = false

    private var geckoView: GeckoView? = null

    





    private lateinit var hostStatus: HostStatusView

    






    private lateinit var rescue: RescueTerminalController

    





    private lateinit var chrome: HostChrome

    















    private val directoryPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val pending = pendingDirectoryResult
            pendingDirectoryResult = null
            val one = uri?.let { listOf(it) }.orEmpty()
            pending?.invoke(one.mapNotNull(::treeUriToGuestPath))
        }

    
    private var directoryPickerMultiple = false

    
    private val treePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = pendingDirectoryResult
            pendingDirectoryResult = null
            val data = result.data
            val uris =
                buildList {
                    data?.data?.let { add(it) }
                    val clips = data?.clipData
                    if (directoryPickerMultiple && clips != null) {
                        for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri)
                    }
                }
            pending?.invoke(uris.mapNotNull(::treeUriToGuestPath))
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val pending = pendingFileResult
            pendingFileResult = null
            pending?.invoke(uris.mapNotNull(::copyToCache))
        }

    private var pendingDirectoryResult: ((List<String>) -> Unit)? = null
    private var pendingFileResult: ((List<File>) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        
        android.util.Log.i(
            "AndCode.Host",
            "app ${BuildConfig.DISPLAY_VERSION} (versionCode=${BuildConfig.VERSION_CODE}, " +
                "bundled OpenCode=${BuildConfig.OPENCODE_VERSION})",
        )

        
        
        
        rendererReadyTimeoutMillis =
            if (savedInstanceState != null) {
                RECREATED_RENDERER_READY_TIMEOUT_MILLIS
            } else {
                RENDERER_READY_TIMEOUT_MILLIS
            }

        
        
        setContentView(R.layout.activity_main)
        
        
        chrome = HostChrome(this)

        
        
        
        chrome.applyMaxRefreshRate()

        
        
        
        
        
        val engine = GeckoHost.sharedRuntime(this)
        
        
        
        
        
        
        
        
        runCatching { engine.configurationChanged(resources.configuration) }
        geckoRuntime = engine
        geckoSession = GeckoHost.newSession().also { it.open(engine) }
        geckoView =
            GeckoHost.createView(
                activity = this,
                container = findViewById(R.id.web_view),
                session = geckoSession!!,
            )
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        hostStatus =
            HostStatusView(
                context = this,
                onRequestStorageAccess = ::requestAllFilesAccess,
                onOpenRescueTerminal = ::openRescueTerminal,
            )
        findViewById<ViewGroup>(R.id.web_view).addView(
            hostStatus,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        
        
        rescue =
            RescueTerminalController(
                activity = this,
                overlayHost = findViewById(R.id.web_host),
                app = application as OpenCodeApplication,
                engineOrigin = ::engineOrigin,
                onChromeChanged = ::onRescueTerminalVisibilityChanged,
            )
        
        
        geckoSession?.let { session ->
            GeckoHost.configure(
                this,
                session,
                onCanGoBack = { canGoBack -> canGoBackInPage = canGoBack },
                onLocation = { url ->
                    if (url != lastKnownPageUrl) exitArmed = false
                    lastKnownPageUrl = url
                    pageAtHome = BackPolicy.isHomePath(url)
                },
                onFirstContentfulPaint = ::onRendererFirstPaint,
                onTitle = ::onRendererTitle,
            )
        }

        
        
        
        
        
        
        
        
        
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val action =
                        BackPolicy.decide(
                            rescueOpen = rescue.isOpen,
                            canGoBackInPage = canGoBackInPage,
                            atHome = pageAtHome,
                            exitArmed = exitArmed,
                        )
                    
                    
                    
                    
                    android.util.Log.i(
                        "AndCode.Back",
                        "back rescue=${rescue.isOpen} canGoBack=$canGoBackInPage atHome=$pageAtHome " +
                            "armed=$exitArmed -> $action",
                    )
                    when (action) {
                        BackPolicy.Action.CloseRescue -> rescue.close()
                        BackPolicy.Action.GoBackInPage -> {
                            val session = geckoSession
                            if (session == null) {
                                armExit()
                            } else {
                                val urlBefore = lastKnownPageUrl
                                session.goBack()
                                
                                
                                Handler(Looper.getMainLooper()).postDelayed(
                                    {
                                        if (geckoSession === session && lastKnownPageUrl == urlBefore) {
                                            armExit()
                                        }
                                    },
                                    700L,
                                )
                            }
                        }
                        BackPolicy.Action.ArmExit -> armExit()
                        BackPolicy.Action.Exit -> {
                            
                            
                            exitArmed = false
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )

        val app = application as OpenCodeApplication

        
        
        applyHostPalette()
        
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.localRuntimeManager.state.collect { hostStatus.render(it) }
            }
        }
        hostStatus.render(app.localRuntimeManager.status())

        
        
        if (hasAllFilesAccess()) {
            engineStarted = true
            RuntimeAutoStartInitializer.startIfInstalled(app)
        } else {
            syncStoragePrompt()
        }

        
        
        loadEngineWhenReady()
    }

    
    private fun evaluateJs(script: String) {
        localUiServer?.pushEval(script)
    }

    





    private fun uiOrigin(): String = "http://127.0.0.1:${ensureUiServer().port}"

    






    




    private fun armExit() {
        exitArmed = true
        Toast.makeText(this, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show()
    }

    private fun ensureUiServer(): LocalUiServer {
        localUiServer?.let { return it }
        
        
        
        var bridge: com.tianercode.opencode.bridge.OpenCodeBridge? = null
        val server =
            LocalUiServer(
                context = this,
                
                
                enginePort = { engineOrigin().substringAfterLast(':').toIntOrNull() ?: DEFAULT_PORT },
                authorization = { sidecarAuthorization() },
                
                
                
                upstream = streamingHttpClient(),
                headInjection = { panelAndBridgeScripts() },
                hostRpc = { ticket, method, argsJson ->
                    val ready = bridge
                    if (ready == null) {
                        org.json.JSONObject().put("accepted", false).put("reason", "host not ready").toString()
                    } else {
                        
                        
                        
                        if (BuildConfig.DEBUG) android.util.Log.i("AndCode.Host", "$method ticket=$ticket $argsJson")
                        ready.dispatchWithTicket(ticket, method, argsJson)
                        org.json.JSONObject().put("accepted", true).toString()
                    }
                },
                
                
                
                preferredPort = LocalUiServer.PREFERRED_PORT,
                
                
                onEngineRequest = ::onRendererEngineRequest,
            )
        server.start()
        localUiServer = server
        
        
        val created =
            com.tianercode.opencode.bridge.OpenCodeBridge(
                this,
                com.tianercode.opencode.bridge.HttpTransport(server),
                this,
                
                
                hostOrigin = { uiOrigin() },
            )
        bridge = created
        hostBridge = created
        return server
    }

    
    private var hostBridge: com.tianercode.opencode.bridge.OpenCodeBridge? = null

    
    private fun sidecarAuthorization(): String? {
        val password = LocalRuntimeServiceCredential.password(this) ?: return null
        val token =
            android.util.Base64.encodeToString(
                "opencode:$password".toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
        return "Basic $token"
    }

    private var localUiServer: LocalUiServer? = null

    

















    private fun loadEngineWhenReady() {
        
        
        
        
        
        
        
        
        
        val target = engineOrigin()
        val up = isEngineUp(target)
        android.util.Log.i("AndCode.Host", "loadEngineWhenReady engineUp=$up uiLoaded=$uiLoaded")
        if (up) {
            loadEngineUi()
            return
        }
        showWaiting()
        val handler = Handler(Looper.getMainLooper())
        val attempt =
            object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed) return
                    if (isEngineUp(target)) {
                        loadEngineUi()
                    } else {
                        handler.postDelayed(this, ENGINE_POLL_MILLIS)
                    }
                }
            }
        handler.postDelayed(attempt, ENGINE_POLL_MILLIS)
    }

    
    private fun showWaiting() {
        
        if (uiLoaded) return
        hostStatus.showWaiting()
    }

    
    private fun hideWaiting() {
        hostStatus.hideWaiting()
    }

    
    
    
    
    
    
    
    
    
    
    
    
    

    














    private fun loadEngineUi() {
        android.util.Log.i("AndCode.Host", "loadEngineUi (uiLoaded was $uiLoaded)")
        uiLoaded = true
        
        
        
        
        
        
        
        
        
        rendererPainted = false
        rendererTitle = null
        rendererReady = false
        rendererStallRetries = 0
        cancelRendererReadyWatchdog()
        bootAttempts = 1
        loadBootPage()
        scheduleWaitingFallback()
    }

    






    private fun loadBootPage() {
        geckoView?.coverUntilFirstPaint(chrome.pageBackground)
        geckoSession?.loadUri("${uiOrigin()}${ensureUiServer().bootPath()}")
    }

    









    private fun onRendererFirstPaint() {
        if (rendererPainted) return
        if (isBootPage()) {
            rendererPainted = true
            recoverBootPage()
            return
        }
        rendererPainted = true
        bootAttempts = 0
        cancelWaitingFallback()
        android.util.Log.i("AndCode.Host", "renderer first paint; hiding waiting screen")
        hideWaiting()
        scheduleRendererReadyWatchdog()
    }

    





    private fun onRendererTitle(title: String?) {
        rendererTitle = title
        if (rendererLooksReady()) {
            rendererStallRetries = 0
            cancelRendererReadyWatchdog()
        }
    }

    





    private fun onRendererEngineRequest() {
        if (rendererReady) return
        rendererReady = true
        rendererStallRetries = 0
        cancelRendererReadyWatchdog()
        android.util.Log.i("AndCode.Host", "renderer app module is alive (engine request observed)")
    }

    private fun rendererLooksReady(): Boolean {
        if (rendererReady) return true
        val title = rendererTitle ?: return false
        return title.isNotBlank() && title != RENDERER_BOOT_TITLE
    }

    







    private fun scheduleRendererReadyWatchdog() {
        if (rendererLooksReady()) return
        cancelRendererReadyWatchdog()
        val watchdog =
            Runnable {
                if (isFinishing || isDestroyed) return@Runnable
                if (rendererLooksReady()) return@Runnable
                android.util.Log.w(
                    "AndCode.Host",
                    "renderer still on its splash after $rendererReadyTimeoutMillis ms (title=$rendererTitle)",
                )
                recoverStalledRenderer()
            }
        rendererReadyWatchdog = watchdog
        Handler(Looper.getMainLooper()).postDelayed(watchdog, rendererReadyTimeoutMillis)
    }

    private fun cancelRendererReadyWatchdog() {
        rendererReadyWatchdog?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        rendererReadyWatchdog = null
    }

    private fun recoverStalledRenderer() {
        cancelRendererReadyWatchdog()
        if (rendererStallRetries < MAX_RENDERER_STALL_RETRIES) {
            rendererStallRetries += 1
            android.util.Log.w("AndCode.Host", "renderer stuck on splash; reloading (attempt $rendererStallRetries)")
            rendererPainted = false
            rendererTitle = null
            rendererReady = false
            hostStatus.showWaiting()
            loadBootPage()
            scheduleWaitingFallback()
            return
        }
        android.util.Log.e("AndCode.Host", "renderer never left its splash; showing host error screen")
        hostStatus.showPageError("界面没有载入完成。请重新打开应用；如果反复出现，复制错误信息并发给开发者。")
    }

    
    private fun isBootPage(): Boolean = lastKnownPageUrl.startsWith("${uiOrigin()}/__boot")

    





    private fun recoverBootPage() {
        cancelWaitingFallback()
        cancelRendererReadyWatchdog()
        if (bootAttempts < MAX_BOOT_ATTEMPTS) {
            bootAttempts += 1
            android.util.Log.w("AndCode.Host", "boot page did not reach the renderer; retrying (attempt $bootAttempts)")
            rendererPainted = false
            
            hostStatus.showWaiting()
            loadBootPage()
            scheduleWaitingFallback()
            return
        }
        android.util.Log.e("AndCode.Host", "boot page failed after $bootAttempts attempts; showing host error screen")
        hostStatus.showPageError("界面没有打开。请重新打开应用；如果反复出现，复制错误信息并发给开发者。")
    }

    






    private fun scheduleWaitingFallback() {
        cancelWaitingFallback()
        val fallback =
            Runnable {
                if (!rendererPainted) {
                    android.util.Log.w("AndCode.Host", "no first paint after 3s; treating the boot page as failed")
                    recoverBootPage()
                }
            }
        waitingFallback = fallback
        Handler(Looper.getMainLooper()).postDelayed(fallback, WAITING_FALLBACK_MILLIS)
    }

    private fun cancelWaitingFallback() {
        waitingFallback?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        waitingFallback = null
    }

    






    private var uiLoaded = false

    
    private var rendererPainted = false

    







    private var bootAttempts = 0

    






    private var rendererTitle: String? = null

    
    private var rendererReadyWatchdog: Runnable? = null

    
    private var rendererStallRetries = 0

    
    private var rendererReadyTimeoutMillis = RENDERER_READY_TIMEOUT_MILLIS

    







    private var rendererReady = false

    
    private var waitingFallback: Runnable? = null

    
    private var engineStarted = false

    













    private fun panelAndBridgeScripts(): String =
        "  <link rel=\"stylesheet\" href=\"/assets/bridge/platform.css\">\n" +
            
            
            "  <script src=\"/assets/bridge/host-shim.js\"></script>\n" +
            
            
            
            
            
            "  <script src=\"/assets/bridge/touch-hover-dismiss.js\"></script>\n" +
            
            "  <script src=\"/assets/bridge/back-route.js\"></script>\n" +
            "  <script src=\"/assets/bridge/android-bridge.js\"></script>\n" +
            "  <script src=\"/assets/bridge/desktop-host.js\"></script>\n</head>"
    
    
    
    
    
    

    









    private fun isEngineUp(origin: String): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val pending = executor.submit<Boolean> {
                runCatching {
                    val connection = URL("$origin/api/info").openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = PROBE_TIMEOUT_MILLIS
                    connection.readTimeout = PROBE_TIMEOUT_MILLIS
                    connection.useCaches = false
                    
                    
                    
                    
                    
                    
                    sidecarAuthorization()?.let { connection.setRequestProperty("Authorization", it) }
                    val code = connection.responseCode
                    connection.disconnect()
                    code == 200
                }.getOrDefault(false)
            }
            
            pending.get(PROBE_TIMEOUT_MILLIS * 2L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun engineOrigin(): String {
        val port = runCatching { LocalRuntimeManifestReader(this).read().port }.getOrNull() ?: DEFAULT_PORT
        return "http://127.0.0.1:$port"
    }


    

    override fun pickDirectories(
        multiple: Boolean,
        onResult: (List<String>) -> Unit,
    ) {
        
        
        
        
        pendingDirectoryResult = onResult
        directoryPickerMultiple = multiple
        if (multiple) {
            treePicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                },
            )
        } else {
            directoryPicker.launch(null)
        }
    }

    override fun pickFiles(
        multiple: Boolean,
        extensions: List<String>?,
        onResult: (List<File>) -> Unit,
    ) {
        pendingFileResult = onResult
        filePicker.launch(arrayOf("*/*"))
    }

    override fun relaunch() {
        packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.let { startActivity(it) }
        finishAndRemoveTask()
    }

    override fun stopEngine() {
        (application as OpenCodeApplication).localRuntimeController.stop()
    }

    override fun applyBackgroundColor(argb: Int) {
        
        chrome.applyPageBackground(argb)
    }

    






    private fun applyHostPalette() {
        chrome.applyPalette()
        hostStatus.applyPalette(chrome.themeBackground)
    }

    
    
    
    
    
    
    
    
    

    
    private fun openRescueTerminal() {
        if (uiLoaded) return
        rescue.open()
    }

    
    private fun onRescueTerminalVisibilityChanged() {
        chrome.setOverlayBackground(
            if (rescue.isOpen) RescueTerminalView.BACKGROUND_COLOR else null,
        )
        applyHostPalette()
    }

    




















    override fun applyZoom(factor: Float) {
        
        
        evaluateJs("document.documentElement.style.zoom=$factor")
    }

    





    override fun reloadPage() {
        geckoSession?.reload()
    }

    







    override fun setFullscreen(enabled: Boolean) {
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    
    override fun runScript(script: String) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i("AndCode.Host", "debugRunScript: $script")
        evaluateJs(script)
    }

    override fun isWindowFocused(): Boolean = hasWindowFocus()

    





    override fun reportRoute(path: String) {
        if (path != lastReportedPath) exitArmed = false
        lastReportedPath = path
        pageAtHome = BackPolicy.isHomePath(path)
        android.util.Log.d("AndCode.Back", "route=$path atHome=$pageAtHome")
    }

    





    private fun copyToCache(uri: Uri): File? =
        runCatching {
            val stream = contentResolver.openInputStream(uri) ?: return null
            val target = File(pickedCache, sanitize(uri.lastPathSegment ?: "attachment"))
            target.outputStream().use { out -> stream.copyTo(out) }
            target
        }.getOrNull()

    private val pickedCache: File by lazy { File(cacheDir, "picked").apply { mkdirs() } }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    




















    private fun treeUriToGuestPath(uri: Uri): String? {
        val authority = uri.authority ?: return null
        if (authority != "com.android.externalstorage.documents") return null
        val id = uri.path?.substringAfterLast("/tree/", "")?.takeIf { it.isNotEmpty() } ?: return null
        val volume = id.substringBefore(':', missingDelimiterValue = "")
        val relative = id.substringAfter(':', missingDelimiterValue = "")
        val root =
            when (volume) {
                "primary" -> DeviceStorage.GUEST_SHARED_ROOT
                else -> "${DeviceStorage.GUEST_VOLUMES_ROOT}/$volume"
            }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    

    



    override fun onResume() {
        super.onResume()
        
        
        exitArmed = false
        syncStoragePrompt()
        val app = application as OpenCodeApplication
        val restore =
            shouldRestoreOnForegroundReturn(
                status = app.localRuntimeManager.status(),
                idleStopInProgress = app.idleStopInProgress.value,
                userStoppedRuntime = app.settings.localRuntimeStoppedByUser,
            )
        if (restore) {
            RuntimeAutoStartInitializer.restoreIfConfigured(app, RuntimeAutoStartTrigger.AppLaunch)
        }
        startEngineIfStorageGranted()
    }

    
















    private fun startEngineIfStorageGranted() {
        val app = application as OpenCodeApplication
        val status = app.localRuntimeManager.status()
        if (status !is LocalRuntimeStatus.AwaitingStoragePermission) return
        if (!DeviceStorageAccess(this).isGranted()) return
        app.applicationScope.launch { runCatching { app.localRuntimeManager.start() } }
    }

    

















    
    private fun requestAllFilesAccess() {
        openDeviceStorageSettings()
    }

    






    private fun hasAllFilesAccess(): Boolean = runCatching { DeviceStorageAccess(this).isGranted() }.getOrDefault(true)

    





    private fun syncStoragePrompt() {
        val granted = hasAllFilesAccess()
        
        hostStatus.showStoragePrompt(!granted)
        if (granted) {
            
            
            if (!uiLoaded && !engineStarted) {
                engineStarted = true
                RuntimeAutoStartInitializer.startIfInstalled(application as OpenCodeApplication)
                loadEngineWhenReady()
            }
        }
    }

    override fun openDeviceStorageSettings(): Boolean {
        val storage = DeviceStorageAccess(this)
        if (storage.isGranted()) return true
        if (!storage.needsSettingsScreen()) {
            return runCatching {
                requestPermissions(storage.runtimePermissions(), REQUEST_ALL_FILES_ACCESS)
            }.isSuccess
        }
        return runCatching { startActivity(storage.settingsIntent()) }.isSuccess ||
            runCatching { startActivity(storage.settingsFallbackIntent()) }.isSuccess
    }

    






    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_ALL_FILES_ACCESS) return
        startEngineIfStorageGranted()
    }

    
    
    
    
    

    







    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        chrome.onConfigurationChanged(newConfig)
        
        
        runCatching { geckoRuntime?.configurationChanged(newConfig) }
        
        hostStatus.applyPalette(chrome.themeBackground)
        
        findViewById<View>(R.id.web_host)?.requestApplyInsets()
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyHostPalette()
        findViewById<View>(R.id.web_host)?.requestApplyInsets()
    }

    override fun onDestroy() {
        
        
        hostStatus.release()
        cancelWaitingFallback()
        cancelRendererReadyWatchdog()
        rescue.dispose()
        
        
        
        
        
        
        
        runCatching { hostBridge?.close() }
        hostBridge = null
        runCatching { localUiServer?.stop() }
        localUiServer = null
        runCatching { geckoSession?.close() }
        geckoSession = null
        super.onDestroy()
    }

    

    private companion object {
        
        const val DEFAULT_PORT = 4097

        
        const val ENGINE_POLL_MILLIS = 500L

        






        const val WAITING_FALLBACK_MILLIS = 3_000L

        
        const val MAX_BOOT_ATTEMPTS = 2

        
        const val RENDERER_BOOT_TITLE = "OpenCode"

        






        const val RENDERER_READY_TIMEOUT_MILLIS = 8_000L

        






        const val RECREATED_RENDERER_READY_TIMEOUT_MILLIS = 2_500L

        
        const val MAX_RENDERER_STALL_RETRIES = 1

        const val PROBE_TIMEOUT_MILLIS = 1_500

        






        const val REQUEST_ALL_FILES_ACCESS = 0x0C0D
    }
}
