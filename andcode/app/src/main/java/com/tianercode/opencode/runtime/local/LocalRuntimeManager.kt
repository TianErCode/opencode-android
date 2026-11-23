package com.tianercode.opencode.runtime.local

import com.tianercode.opencode.runtime.LocalAgent
import com.tianercode.opencode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class LocalRuntimeMetadata(
    @SerialName("version") val version: String,
    @SerialName("port") val port: Int,
    @SerialName("installedAt") val installedAt: Long,
    @SerialName("runtimeVersion") val runtimeVersion: String = "legacy",
    @SerialName("abi") val abi: String = "unknown",
    





    @SerialName("components") val components: Set<String> = setOf(LocalAgent.OPEN_CODE.id),
    
    @SerialName("fullDevelopmentToolsInstalled") val fullDevelopmentToolsInstalled: Boolean = true,
    






    @SerialName("fullDebianDevelopmentToolsInstalled") val fullDebianDevelopmentToolsInstalled: Boolean = false,
) {
    fun has(agent: LocalAgent): Boolean = agent.id in components

    fun hasFullDevelopmentTools(): Boolean = fullDevelopmentToolsInstalled

    fun with(agent: LocalAgent): LocalRuntimeMetadata = copy(components = components + agent.id)

    fun without(agent: LocalAgent): LocalRuntimeMetadata = copy(components = components - agent.id)
}

class LocalRuntimeManager(
    private val runtimeDirectory: File,
    private val abi: String,
    










    private val storageGranted: () -> Boolean = { true },
    






    private val deviceDnsServers: () -> List<String> = { emptyList() },
    private val portProbe: (Int) -> Boolean = ::defaultPortProbe,
    private val installer: LocalRuntimeInstaller? = null,
    







    private val bundledRuntimeVersion: () -> String? = { null },
    private val processLauncher: LocalRuntimeProcessLauncher? = null,
    
    private val processAlive: () -> Boolean = { processLauncher?.isRunning() == true },
    private val updateEngine: LocalRuntimeUpdateEngine? = null,
    private val runtimeOperations: LocalRuntimeOperations? = null,
    







    private val systemPrompt: () -> String? = { null },
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
) {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(computeStatus())
    val state: StateFlow<LocalRuntimeStatus> = mutableState.asStateFlow()

    















    private val operationsInFlight = AtomicInteger(0)

    




    private suspend fun <T> tracked(block: suspend () -> T): T {
        operationsInFlight.incrementAndGet()
        try {
            return block()
        } finally {
            operationsInFlight.decrementAndGet()
        }
    }


    fun status(): LocalRuntimeStatus {
        val operation = mutableState.value
        
        
        
        if (operationsInFlight.get() > 0 &&
            (
                operation is LocalRuntimeStatus.Installing ||
                    operation is LocalRuntimeStatus.Starting ||
                    operation is LocalRuntimeStatus.Updating
            )
        ) {
            return operation
        }
        return computeStatus().also { mutableState.value = it }
    }

    fun installedPort(): Int? = readMetadata()?.port

    fun isHealthy(): Boolean = installedPort()?.let(portProbe) == true

    fun setOnExit(callback: ((Int?, Long?, Long) -> Unit)?) {
        processLauncher?.setOnExit(callback)
    }

    fun lastExitCode(): Int? = processLauncher?.exitRecord()?.first

    fun lastExitAtMillis(): Long? = processLauncher?.exitRecord()?.second

    fun restartCount(): Int = processLauncher?.restartCount() ?: 0

    suspend fun installAndStart(
        agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE),
        installFullDevelopmentTools: Boolean = false,
    ): Result<LocalRuntimeStatus.Ready> =
        tracked {
        operationMutex.withLock {
            val configuredInstaller =
                installer
                    ?: return@withLock Result.failure(IllegalStateException("Local runtime installer is not configured"))
            runCatching {
                val installed =
                    configuredInstaller.install(agents, installFullDevelopmentTools) { progress, step, agent ->
                        mutableState.value = LocalRuntimeStatus.Installing(progress, step, agent)
                    }
                mutableState.value = LocalRuntimeStatus.Stopped(installed.metadata.version, installed.metadata.port)
                startInstalled(installed)
            }.onFailure { error ->
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                if (error is CancellationException) throw error
                
                
                
                
                
                
                
                android.util.Log.w(LOG_TAG, "install failed", error)
                publishBroken(error.message ?: messages.installFailed, error)
            }
        }
        }

    suspend fun start(): Result<LocalRuntimeStatus.Ready> =
        tracked {
            operationMutex.withLock {
                startLocked()
            }
        }

    suspend fun ensureRunning(): Result<LocalRuntimeStatus.Ready> =
        tracked {
        operationMutex.withLock {
            
            
            
            
            
            runCatching { updateEngine?.recover() }
            val metadata =
                readMetadata()
                    ?: return@withLock Result.failure(IllegalStateException("Local runtime is not installed"))
            
            
            
            if (runtimeVersionNeedsReprovision(metadata)) {
                android.util.Log.i(
                    LOG_TAG,
                    "ensureRunning: runtime version '${metadata.runtimeVersion}' != bundled; reinstalling from this APK",
                )
                
                return@withLock reinstallLocked(preserveOptionalDevelopmentTools = false)
            }
            val bundledVersion = installer?.bundledOpenCodeVersion()
            
            
            
            
            if (bundledVersion != null &&
                compareRuntimeVersions(metadata.version, bundledVersion) < 0
            ) {
                
                return@withLock reinstallLocked(preserveOptionalDevelopmentTools = false)
            }
            
            
            
            if (portProbe(metadata.port) || processAlive()) {
                return@withLock Result.success(
                    LocalRuntimeStatus.Ready(metadata.version, metadata.port).also { mutableState.value = it },
                )
            }
            withContext(Dispatchers.IO) { processLauncher?.stop() }
            startLocked()
        }
        }

    suspend fun stop(): Result<LocalRuntimeStatus.Stopped> =
        operationMutex.withLock {
            runCatching {
                withContext(Dispatchers.IO) { processLauncher?.stop() }
                val metadata = readMetadata() ?: error("Local runtime metadata is missing")
                LocalRuntimeStatus.Stopped(metadata.version, metadata.port).also { mutableState.value = it }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                publishBroken(error.message ?: messages.stopFailed, error)
            }
        }

    suspend fun deleteRuntime(): Result<LocalRuntimeStatus.NotInstalled> =
        operationMutex.withLock {
            runCatching {
                withContext(Dispatchers.IO) {
                    processLauncher?.stop()
                    if (runtimeDirectory.exists()) {
                        require(runtimeDirectory.deleteRecursively()) {
                            messages.deleteIncomplete
                        }
                    }
                }
                LocalRuntimeStatus.NotInstalled.also { mutableState.value = it }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                publishBroken(error.message ?: messages.deleteFailed, error)
            }
        }

    suspend fun reinstall(): Result<LocalRuntimeStatus.Ready> =
        tracked {
            
            
            operationMutex.withLock { reinstallLocked(preserveOptionalDevelopmentTools = true) }
        }

    















    private suspend fun reinstallLocked(
        preserveOptionalDevelopmentTools: Boolean = true,
    ): Result<LocalRuntimeStatus.Ready> =
        run {
            withContext(Dispatchers.IO) { processLauncher?.stop() }
            val previousMetadata = readMetadata()
            val hadOptionalDevelopmentTools = previousMetadata?.hasFullDevelopmentTools() == true
            File(runtimeDirectory, METADATA_FILE).delete()
            val configuredInstaller =
                installer
                    ?: return Result.failure(IllegalStateException("Local runtime installer is not configured"))
            runCatching {
                val installed =
                    configuredInstaller.install(
                        agents =
                            previousMetadata?.components
                                ?.mapNotNull(LocalAgent::fromId)
                                ?.toSet()
                                ?.takeIf(Set<LocalAgent>::isNotEmpty)
                                ?: setOf(LocalAgent.OPEN_CODE),
                        installFullDevelopmentTools = preserveOptionalDevelopmentTools && hadOptionalDevelopmentTools,
                    ) { progress, step, agent ->
                        mutableState.value = LocalRuntimeStatus.Installing(progress, step, agent)
                    }
                
                
                if (hadOptionalDevelopmentTools && !preserveOptionalDevelopmentTools) {
                    android.util.Log.w(
                        LOG_TAG,
                        "reinstall: rebuilt for the new runtime version without the optional development tools " +
                            "(they are not in the APK and restoring them needs the network); " +
                            "metadata records them as not installed so the panel offers the explicit install",
                    )
                    runCatching { messages.notifyRuntimeRebuiltWithoutDevelopmentTools() }
                }
                startInstalled(installed)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                publishBroken(error.message ?: messages.reinstallFailed, error)
            }
        }

    




















    suspend fun checkForUpdate(): Result<LocalRuntimeUpdateCheck> =
        operationMutex.withLock {
            updateEngine
                ?: return@withLock Result.failure(IllegalStateException("Local runtime updater is not configured"))
            val metadata =
                currentMetadataForOperation()
                    ?: return@withLock Result.failure(IllegalStateException("Local runtime is not installed"))
            runCatching { pinnedUpdateCheck(metadata) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                }
        }

    











    private fun pinnedUpdateCheck(metadata: LocalRuntimeMetadata): LocalRuntimeUpdateCheck {
        val installedVersion = metadata.version
        val pinned =
            installer?.bundledOpenCodeVersion()
                ?: return LocalRuntimeUpdateCheck.UpToDate(installedVersion, installedVersion)
        val comparison = compareRuntimeVersions(installedVersion, pinned)
        if (comparison == 0 && !runtimeVersionNeedsReprovision(metadata)) {
            return LocalRuntimeUpdateCheck.UpToDate(installedVersion, pinned)
        }
        val asset = installer?.bundledOpenCodeAsset()
            ?: return LocalRuntimeUpdateCheck.UpToDate(installedVersion, installedVersion)
        return LocalRuntimeUpdateCheck.Available(
            currentVersion = installedVersion,
            release =
                LocalRuntimeRelease(
                    version = pinned,
                    
                    
                    releaseNotes = "",
                    asset = asset,
                ),
        )
    }

    




















    internal fun runtimeVersionNeedsReprovision(metadata: LocalRuntimeMetadata): Boolean {
        val bundled =
            runCatching { bundledRuntimeVersion() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: return false
        return metadata.runtimeVersion != bundled
    }

    suspend fun updateToLatest(): Result<LocalRuntimeStatus.Ready> =
        tracked {
            operationMutex.withLock {
                updateToLatestLocked()
            }
        }

    suspend fun rollback(): Result<LocalRuntimeStatus.Ready> =
        tracked {
            operationMutex.withLock {
                rollbackLocked()
            }
        }

    private suspend fun updateToLatestLocked(): Result<LocalRuntimeStatus.Ready> {
        val engine =
            updateEngine
                ?: return Result.failure(IllegalStateException("Local runtime updater is not configured"))
        val current =
            currentMetadataForOperation()
                ?: return Result.failure(IllegalStateException("Local runtime is not installed"))
        
        
        
        
        
        if (runtimeVersionNeedsReprovision(current)) {
            android.util.Log.i(
                LOG_TAG,
                "update: runtime version '${current.runtimeVersion}' != bundled; reinstalling from this APK",
            )
            return reinstallLocked(preserveOptionalDevelopmentTools = false)
        }
        val check =
            
            
            
            runCatching { pinnedUpdateCheck(current) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    return Result.failure(error)
                }

        if (check is LocalRuntimeUpdateCheck.UpToDate) {
            val ready =
                if (portProbe(current.port)) {
                    LocalRuntimeStatus.Ready(current.version, current.port)
                        .also { mutableState.value = it }
                } else {
                    startForOperation()
                }
            return Result.success(ready)
        }

        val available = check as LocalRuntimeUpdateCheck.Available
        val targetVersion = available.release.version
        mutableState.value =
            LocalRuntimeStatus.Updating(
                currentVersion = current.version,
                targetVersion = targetVersion,
                progress = null,
                step = messages.preparingUpdate(),
            )
        val prepared =
            runCatching {
                engine.prepare(available.release) { progress, step ->
                    mutableState.value =
                        LocalRuntimeStatus.Updating(
                            currentVersion = current.version,
                            targetVersion = targetVersion,
                            progress = progress,
                            step = step,
                        )
                }
            }.getOrElse { error ->
                restoreStateBeforeMutation(current)
                if (error is CancellationException) throw error
                return Result.failure(error)
            }

        return try {
            stopForOperation()
            engine.activate(prepared)
            val ready = startForOperation()
            engine.commit()
            mutableState.value = ready
            Result.success(ready)
        } catch (error: Throwable) {
            val recovery =
                withContext(NonCancellable) {
                    restoreAfterFailedMutation(
                        engine = engine,
                        fallbackMetadata = current,
                        attemptedVersion = targetVersion,
                        originalError = error,
                    )
                }
            if (error is CancellationException) throw error
            recovery
        }
    }

    private suspend fun rollbackLocked(): Result<LocalRuntimeStatus.Ready> {
        val engine =
            updateEngine
                ?: return Result.failure(IllegalStateException("Local runtime updater is not configured"))
        val current =
            currentMetadataForOperation()
                ?: return Result.failure(IllegalStateException("Local runtime is not installed"))
        val targetVersion =
            runCatching { engine.rollbackVersion() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    return Result.failure(error)
                }
                ?: return Result.failure(IllegalStateException("Rollback version is unavailable"))

        mutableState.value =
            LocalRuntimeStatus.Updating(
                currentVersion = current.version,
                targetVersion = targetVersion,
                progress = null,
                step = messages.rollingBackTo(targetVersion),
            )
        return try {
            stopForOperation()
            engine.rollback()
            val ready = startForOperation()
            engine.commit()
            mutableState.value = ready
            Result.success(ready)
        } catch (error: Throwable) {
            val recovery =
                withContext(NonCancellable) {
                    restoreAfterFailedMutation(
                        engine = engine,
                        fallbackMetadata = current,
                        attemptedVersion = targetVersion,
                        originalError = error,
                    )
                }
            if (error is CancellationException) throw error
            recovery
        }
    }

    private suspend fun restoreAfterFailedMutation(
        engine: LocalRuntimeUpdateEngine,
        fallbackMetadata: LocalRuntimeMetadata,
        attemptedVersion: String,
        originalError: Throwable,
    ): Result<LocalRuntimeStatus.Ready> {
        runCatching { stopForOperation() }
            .exceptionOrNull()
            ?.let(originalError::addSuppressed)
        val recoveryError = runCatching { engine.recover() }.exceptionOrNull()
        recoveryError?.let(originalError::addSuppressed)
        val restoredMetadata = currentMetadataForOperation() ?: fallbackMetadata
        val restart = runCatching { startForOperation() }
        return restart.fold(
            onSuccess = { ready ->
                mutableState.value = ready
                Result.failure(originalError)
            },
            onFailure = { restartError ->
                originalError.addSuppressed(restartError)
                publishBroken(
                    messages.restoredButCannotStart(restoredMetadata.version, restartError.message.orEmpty()),
                    restartError,
                )
                Result.failure(originalError)
            },
        )
    }

    private fun restoreStateBeforeMutation(metadata: LocalRuntimeMetadata) {
        mutableState.value =
            if (portProbe(metadata.port)) {
                LocalRuntimeStatus.Ready(metadata.version, metadata.port)
            } else {
                LocalRuntimeStatus.Stopped(metadata.version, metadata.port)
            }
    }

    private fun currentMetadataForOperation(): LocalRuntimeMetadata? = runtimeOperations?.currentMetadata() ?: readMetadata()

    private suspend fun stopForOperation() {
        val operations = runtimeOperations
        if (operations != null) {
            operations.stop()
        } else {
            withContext(Dispatchers.IO) { processLauncher?.stop() }
        }
    }

    private suspend fun startForOperation(): LocalRuntimeStatus.Ready {
        runtimeOperations?.let { return it.start() }
        val configuredInstaller =
            installer
                ?: error("Local runtime installer is not configured")
        val installed =
            configuredInstaller.installedRuntime()
                ?: error("Local runtime is not installed")
        return startInstalled(installed)
    }

    private suspend fun startLocked(): Result<LocalRuntimeStatus.Ready> {
        
        
        
        
        if (!storageGranted()) {
            mutableState.value = LocalRuntimeStatus.AwaitingStoragePermission
            return Result.failure(StorageAccessRequired)
        }
        
        
        
        
        val installedMetadata = readMetadata()
        if (installedMetadata != null && runtimeVersionNeedsReprovision(installedMetadata)) {
            android.util.Log.i(
                LOG_TAG,
                "start: runtime version '${installedMetadata.runtimeVersion}' != bundled; reinstalling from this APK",
            )
            
            return reinstallLocked(preserveOptionalDevelopmentTools = false)
        }
        return runCatching {
            updateEngine?.recover()
            runtimeOperations?.let { operations ->
                val ready = operations.start()
                mutableState.value = ready
                return@runCatching ready
            }
            val configuredInstaller =
                installer
                    ?: error("Local runtime installer is not configured")
            withContext(Dispatchers.IO) {
                configuredInstaller.recoverInterruptedActivation()
            }
            val installed =
                configuredInstaller.installedRuntime()
                    ?: error("Local runtime is not installed")
            startInstalled(installed)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            publishBroken(error.message ?: messages.startFailed, error)
        }
    }

    




    internal suspend fun startInstalled(installed: LocalRuntimeInstaller.InstalledRuntime): LocalRuntimeStatus.Ready =
        withContext(Dispatchers.IO) {
            
            
            
            
            
            
            requireStorageAccess()
            val launcher =
                processLauncher
                    ?: error("Local runtime process launcher is not configured")
            mutableState.value =
                LocalRuntimeStatus.Starting(
                    installed.metadata.version,
                    installed.metadata.port,
                )
            
            
            runCatching { installer?.provisionGuestCapabilitiesForExistingInstall() }
            
            
            
            
            
            
            
            
            runCatching { RuntimeGuestConfig.ensureUpToDate(installed.rootfs, deviceDnsServers()) }
            
            applyOpenCodeSystemPrompt(installed.rootfs, systemPrompt())
            if (!portProbe(installed.metadata.port)) launcher.start(installed)
            val ready =
                LocalRuntimeStatus.Ready(
                    installed.metadata.version,
                    installed.metadata.port,
                )
            mutableState.value = ready
            ready
        }

    





    private fun requireStorageAccess() {
        if (storageGranted()) return
        mutableState.value = LocalRuntimeStatus.AwaitingStoragePermission
        throw StorageAccessRequired
    }

    









    private fun publishBroken(
        reason: String,
        cause: Throwable,
    ) {
        if (cause is StorageAccessRequired) return
        mutableState.value = LocalRuntimeStatus.Broken(reason)
    }

    private fun computeStatus(): LocalRuntimeStatus {
        if (abi !in SUPPORTED_ABIS) return LocalRuntimeStatus.UnsupportedAbi(abi)
        val metadataFile = File(runtimeDirectory, METADATA_FILE)
        
        
        
        if (!metadataFile.isFile) return LocalRuntimeStatus.NotInstalled
        if (!storageGranted()) return LocalRuntimeStatus.AwaitingStoragePermission
        val metadata =            runCatching {
                json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
            }.getOrElse { error ->
                return LocalRuntimeStatus.Broken("Runtime metadata is invalid: ${error.message}")
            }
        
        
        if (!metadata.has(LocalAgent.OPEN_CODE)) return LocalRuntimeStatus.NotInstalled
        val rootfs = File(runtimeDirectory, "environment/rootfs")
        val openCode = File(rootfs, "usr/local/bin/opencode")
        if (!rootfs.isDirectory || !openCode.isFile) {
            return LocalRuntimeStatus.Broken(messages.missingFiles)
        }
        if (metadata.version.isBlank() || metadata.port !in 1..65535) {
            return LocalRuntimeStatus.Broken("Runtime metadata contains invalid values")
        }
        if (portProbe(metadata.port)) return LocalRuntimeStatus.Ready(metadata.version, metadata.port)
        
        
        
        if (processAlive()) {
            return LocalRuntimeStatus.Ready(metadata.version, metadata.port)
        }
        return LocalRuntimeStatus.Stopped(metadata.version, metadata.port)
    }

    private fun readMetadata(): LocalRuntimeMetadata? {
        val metadataFile = File(runtimeDirectory, METADATA_FILE)
        if (!metadataFile.isFile) return null
        return runCatching {
            json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
        }.getOrNull()
    }

    companion object {
        private const val METADATA_FILE = "metadata.json"

        
        private const val LOG_TAG = "AndCode.Runtime"
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

        









        private const val PROBE_TIMEOUT_MILLIS = 2_500

        fun defaultPortProbe(port: Int): Boolean =
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MILLIS)
                }
                true
            }.getOrDefault(false)
    }
}









private object StorageAccessRequired : IllegalStateException("All-files access is not granted")
