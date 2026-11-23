package com.tianercode.opencode

import android.app.Application
import android.os.Build
import com.tianercode.opencode.core.lifecycle.AppForeground
import com.tianercode.opencode.core.lifecycle.ProcessLifecycleAppForeground
import com.tianercode.opencode.core.storage.DeviceStorage
import com.tianercode.opencode.core.storage.DeviceStorageAccess
import com.tianercode.opencode.core.runtime.RuntimeWorkTracker
import com.tianercode.opencode.data.connection.SecureSettingsRepository
import com.tianercode.opencode.runtime.LocalRuntimeStatus
import com.tianercode.opencode.runtime.local.AndroidLocalRuntimeMessages
import com.tianercode.opencode.runtime.local.CustomProviderStore
import com.tianercode.opencode.runtime.local.DefaultLocalRuntimeUpdateEngine
import com.tianercode.opencode.runtime.local.DeviceDns
import com.tianercode.opencode.runtime.local.EmbeddedCommandSuite
import com.tianercode.opencode.runtime.local.LocalProviderCredentialStore
import com.tianercode.opencode.runtime.local.LocalRuntimeAccessCoordinator
import com.tianercode.opencode.runtime.local.LocalRuntimeCommandRunner
import com.tianercode.opencode.runtime.local.LocalRuntimeInstaller
import com.tianercode.opencode.runtime.local.LocalRuntimeManager
import com.tianercode.opencode.runtime.local.LocalRuntimeManifestReader
import com.tianercode.opencode.runtime.local.LocalRuntimeProcessLauncher
import com.tianercode.opencode.runtime.local.LocalRuntimeReleaseClient
import com.tianercode.opencode.runtime.local.LocalRuntimeServiceController
import com.tianercode.opencode.runtime.local.LocalRuntimeUpdateEngine
import com.tianercode.opencode.runtime.local.LocalRuntimeUpdater
import com.tianercode.opencode.runtime.local.VerifiedRuntimeDownloader
import com.tianercode.opencode.runtime.local.downloadHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File










class OpenCodeApplication : Application() {
    
    lateinit var settings: SecureSettingsRepository
        private set

    
    lateinit var runtimeWork: RuntimeWorkTracker
        private set

    
    lateinit var appForeground: AppForeground
        private set

    private val mutableIdleStopInProgress = MutableStateFlow(false)

    







    val idleStopInProgress: StateFlow<Boolean> = mutableIdleStopInProgress.asStateFlow()

    fun setIdleStopInProgress(inProgress: Boolean) {
        mutableIdleStopInProgress.value = inProgress
    }

    lateinit var localRuntimeManager: LocalRuntimeManager
        private set

    lateinit var localRuntimeController: LocalRuntimeServiceController
        private set

    









    lateinit var localRuntimeCommandRunner: LocalRuntimeCommandRunner
        private set

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        settings = SecureSettingsRepository(this)
        runtimeWork = RuntimeWorkTracker()
        appForeground = ProcessLifecycleAppForeground.install()

        
        
        
        
        
        
        
        
        DeviceStorage.install(DeviceStorageAccess(this)::mounts)

        val runtimeDirectory = File(filesDir, "runtime")
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val accessCoordinator = LocalRuntimeAccessCoordinator()

        val credentialStore = LocalProviderCredentialStore(settings)
        val customProviderStore = CustomProviderStore(settings)

        val installer =
            LocalRuntimeInstaller(
                context = this,
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                accessCoordinator = accessCoordinator,
            )

        val launcher =
            LocalRuntimeProcessLauncher(
                runtimeDirectory = runtimeDirectory,
                portProbe = LocalRuntimeManager::defaultPortProbe,
                
                
                beforeStart = { installed ->
                    
                    
                    runCatching { credentialStore.syncToRuntime(installed.rootfs) }
                    runCatching { customProviderStore.syncToRuntime(installed.rootfs) }
                },
            )

        
        
        
        val httpClient = downloadHttpClient()
        val verifiedDownloader = VerifiedRuntimeDownloader(httpClient)
        val messages = AndroidLocalRuntimeMessages(this)
        val commandRunner =
            LocalRuntimeCommandRunner(
                runtimeDirectory = runtimeDirectory,
                installedRuntimeProvider = installer::installedRuntime,
                accessCoordinator = accessCoordinator,
                messages = messages,
            )
        
        localRuntimeCommandRunner = commandRunner

        
        
        val updater =
            LocalRuntimeUpdater(
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                downloadAsset = { asset, destination, progress ->
                    verifiedDownloader.download(
                        
                        
                        
                        urls = listOf(asset.url),
                        destination = destination,
                        expectedSha256 = asset.sha256,
                        expectedSizeBytes = asset.sizeBytes,
                        
                        
                        
                        onProgress = { reported -> progress(reported.fraction) },
                    )
                },
                candidateVersionProvider = { candidate ->
                    
                    
                    val result =
                        commandRunner.runShell(
                            commandText = "/usr/local/bin/${candidate.name} --version",
                            timeoutSeconds = 30L,
                        )
                    require(result.exitCode == 0) {
                        "OpenCode update candidate validation failed: ${result.output}"
                    }
                    result.output.lineSequence().firstOrNull(String::isNotBlank)
                        ?: error("OpenCode update candidate returned no version")
                },
                accessCoordinator = accessCoordinator,
                messages = messages,
            )

        val updateEngine: LocalRuntimeUpdateEngine =
            DefaultLocalRuntimeUpdateEngine(
                releaseClient = LocalRuntimeReleaseClient(httpClient),
                updater = updater,
            )

        localRuntimeManager =
            LocalRuntimeManager(
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                
                
                
                storageGranted = { DeviceStorageAccess(this).isGranted() },
                
                
                
                deviceDnsServers = { DeviceDns.servers(this) },
                portProbe = LocalRuntimeManager::defaultPortProbe,
                installer = installer,
                processLauncher = launcher,
                processAlive = { launcher.isRunning() },
                
                
                
                
                bundledRuntimeVersion = {
                    runCatching { LocalRuntimeManifestReader(this).read().runtimeVersion }.getOrNull()
                },
                updateEngine = updateEngine,
            )

        localRuntimeController = LocalRuntimeServiceController(this)

        
        
        runCatching { EmbeddedCommandSuite(this, runtimeDirectory, abi).ensureInstalled() }
    }

    
    fun hasInstalledRuntime(): Boolean =
        localRuntimeManager.status() !is LocalRuntimeStatus.NotInstalled
}
