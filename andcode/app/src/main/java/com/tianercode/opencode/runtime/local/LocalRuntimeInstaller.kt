package com.tianercode.opencode.runtime.local

import android.content.res.AssetManager
import android.content.Context
import android.system.Os
import com.tianercode.opencode.R
import com.tianercode.opencode.runtime.LocalAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference















class LocalRuntimeInstaller(
    private val context: Context,
    private val runtimeDirectory: File,
    private val abi: String,
    
    
    private val manifestReader: LocalRuntimeManifestReader = LocalRuntimeManifestReader(context),
    




    private val sourceProbe: RuntimeSourceProbe = RuntimeSourceProbe(),
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
) {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    data class InstalledRuntime(
        val metadata: LocalRuntimeMetadata,
        val commandSuite: EmbeddedCommandSuite.Paths,
        val rootfs: File,
        
        val openCode: File?,
    )

    





    private fun legacyNetworking(): RouteALegacyNetworking =
        RouteALegacyNetworking(
            context = context,
            abi = abi,
            sourceProbe = sourceProbe,
            alpineVersion = { manifestReader.read().alpineVersion },
            apkMirrors = APK_MIRROR_BASES,
            apkRepositories = APK_REPOSITORIES,
        )

    






    suspend fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE),
        installFullDevelopmentTools: Boolean = false,
        





        onProgress: (Float?, String, LocalAgent?) -> Unit = { _, _, _ -> },
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            
            
            
            
            
            
            
            val installStartedAt = System.currentTimeMillis()
            var phaseStartedAt = installStartedAt
            fun mark(phase: String) {
                val now = System.currentTimeMillis()
                android.util.Log.i(
                    LOG_TAG,
                    "phase: $phase +${now - phaseStartedAt}ms (total ${now - installStartedAt}ms)",
                )
                phaseStartedAt = now
            }
            val onShared: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, null) }
            runtimeDirectory.mkdirs()
            onShared(0.02f, context.getString(R.string.install_step_preparing_command_env))
            val existingMetadata = installedMetadata()
            val requestedAgents =
                agents + (
                    existingMetadata?.let {
                            existing ->
                        LocalAgent.entries.filter(existing::has)
                    } ?: emptyList()
                )
            
            
            val includeFullDevelopmentTools =
                installFullDevelopmentTools || existingMetadata?.fullDevelopmentToolsInstalled == true
            require(requestedAgents.isNotEmpty()) { "At least one agent must be selected" }
            val commandSuite = EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
            val manifest = manifestReader.read()
            val architecture = manifest.architecture(abi)
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val staging = File(runtimeDirectory, "environment.staging")
            val active = File(runtimeDirectory, "environment")
            val rollback = File(runtimeDirectory, "environment.rollback")
            accessCoordinator.write {
                recoverInterruptedRuntimeEnvironment(
                    active = active,
                    rollback = rollback,
                    topLevelMetadata = File(runtimeDirectory, METADATA_FILE),
                )
            }
            staging.deleteRecursively()
            staging.mkdirs()

            try {
                val alpineArchive = File(cache, "alpine-${manifest.alpineVersion}-$abi.tar.gz")
                val withOpenCode = LocalAgent.OPEN_CODE in requestedAgents
                val openCodeArchive =
                    File(cache, "opencode-${manifest.openCodeVersion}-$abi.tar.gz").takeIf { withOpenCode }

                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                val packagedOnly = !includeFullDevelopmentTools
                val apkMirrors =
                    if (packagedOnly) {
                        
                        
                        emptyList()
                    } else {
                        mark("source-probe")
                        legacyNetworking().rankedApkMirrors(onShared).also { mark("apk-mirrors") }
                    }

                
                
                
                val alpineCopy =
                    async {
                        materialize(
                            assetName = bundledAssetName(manifest.alpineVersion, abi, ALPINE_ASSET),
                            destination = alpineArchive,
                            expectedSha256 = architecture.alpineSha256,
                            startProgress = 0.05f,
                            endProgress = 0.22f,
                            label = context.getString(R.string.install_step_preparing_alpine),
                            onProgress = onShared,
                        )
                    }
                val openCodeCopy =
                    openCodeArchive?.let { archive ->
                        async {
                            materialize(
                                assetName = bundledAssetName(manifest.openCodeVersion, abi, ENGINE_ASSET),
                                destination = archive,
                                expectedSha256 = architecture.openCodeSha256,
                                startProgress = 0.24f,
                                endProgress = 0.72f,
                                label = context.getString(R.string.install_step_preparing_opencode),
                                onProgress = onShared,
                            )
                        }
                    }
                alpineCopy.await()
                openCodeCopy?.await()

                val rootfs = File(staging, "rootfs").apply { mkdirs() }
                mark("downloads")
                onShared(0.75f, context.getString(R.string.install_step_extracting_linux_env))
                alpineArchive.inputStream().use { RuntimeArchive.extractTarGz(it, rootfs) }

                val openCodeBinary =
                    openCodeArchive?.let { archive ->
                        onShared(0.85f, context.getString(R.string.install_step_extracting_opencode))
                        extractOpenCode(archive, staging, rootfs)
                    }
                mark("extract")
                configureRootfs(rootfs, commandSuite, apkMirrors)
                
                
                
                carryOverHomeDirectory(File(active, "rootfs"), rootfs)
                ensureOpenCodeAgentContext(rootfs, context)
                onShared(
                    0.91f,
                    context.getString(
                        if (includeFullDevelopmentTools) {
                            R.string.install_step_installing_dev_tools
                        } else {
                            R.string.install_step_installing_runtime_tools
                        },
                    ),
                )
                
                
                
                val toolsLabel =
                    if (includeFullDevelopmentTools) {
                        R.string.install_step_installing_dev_tools
                    } else {
                        R.string.install_step_installing_runtime_tools
                    }
                val requestedPackages =
                    if (includeFullDevelopmentTools) {
                        REQUIRED_RUNTIME_PACKAGES + OPTIONAL_DEVELOPMENT_PACKAGES
                    } else {
                        REQUIRED_RUNTIME_PACKAGES
                    }
                
                
                
                
                
                
                
                mark("configure")
                val bundledRepository =
                    if (requestedPackages == REQUIRED_RUNTIME_PACKAGES) {
                        android.util.Log.i(LOG_TAG, "phase: unpacking the bundled package repository")
                        unpackBundledPackages(
                            onProgress = { progress, step -> onShared(progress, step) },
                        )
                    } else {
                        android.util.Log.i(
                            LOG_TAG,
                            "phase: development tools requested - using the network, the package does not carry them",
                        )
                        null
                    }
                mark("bundled-repository")
                installPackages(
                    rootfs = rootfs,
                    suite = commandSuite,
                    packages = requestedPackages,
                    localRepository = bundledRepository,
                    onProgress = { progress, step -> onShared(progress, step) },
                    
                    
                    
                    
                    stepFor = { count ->
                        val label = context.getString(toolsLabel)
                        if (count <= 0) label
                        else label + "  ·  " + count + " " + context.getString(R.string.install_unit_packages)
                    },
                    
                    allowNetworkFallback = includeFullDevelopmentTools,
                )
                val metadata =
                    LocalRuntimeMetadata(
                        version = if (withOpenCode) manifest.openCodeVersion else "",
                        port = manifest.port,
                        installedAt = System.currentTimeMillis(),
                        runtimeVersion = manifest.runtimeVersion,
                        abi = abi,
                        components = requestedAgents.map(LocalAgent::id).toSet(),
                        fullDevelopmentToolsInstalled = includeFullDevelopmentTools,
                        fullDebianDevelopmentToolsInstalled = false,
                    )
                File(staging, METADATA_FILE).writeText(json.encodeToString(metadata))
                mark("install-packages")
                onShared(0.96f, context.getString(R.string.install_step_activating_runtime))
                accessCoordinator.write {
                    activateRuntimeEnvironment(
                        active = active,
                        staging = staging,
                        rollback = rollback,
                        finalizeActivation = { activated ->
                            replaceFileAtomically(
                                source = File(activated, METADATA_FILE),
                                destination = File(runtimeDirectory, METADATA_FILE),
                            )
                        },
                    )
                }
                onShared(1f, context.getString(R.string.install_step_done))
                InstalledRuntime(
                    metadata,
                    commandSuite,
                    File(active, "rootfs"),
                    openCodeBinary?.let { File(active, "rootfs/usr/local/bin/opencode") },
                )
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }

    fun recoverInterruptedActivation(): Boolean =
        accessCoordinator.write {
            recoverInterruptedRuntimeEnvironment(
                active = File(runtimeDirectory, "environment"),
                rollback = File(runtimeDirectory, "environment.rollback"),
                topLevelMetadata = File(runtimeDirectory, METADATA_FILE),
            )
        }

    





    fun installedRuntime(): InstalledRuntime? =
        accessCoordinator.write {
            val active = File(runtimeDirectory, "environment")
            normalizeRuntimeSymlinks(active)
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            val rootfs = File(active, "rootfs")
            if (!metadataFile.isFile || !rootfs.isDirectory) return@write null
            val metadata =
                runCatching {
                    json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
                }.getOrNull() ?: return@write null
            val commandSuite =
                runCatching {
                    EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
                }.getOrNull() ?: return@write null
            InstalledRuntime(
                metadata,
                commandSuite,
                rootfs,
                File(rootfs, "usr/local/bin/opencode").takeIf { it.isFile },
            )
        }?.also { installed ->
            ensureOpenCodeAgentContext(installed.rootfs, context)
        }

    
    fun installedMetadata(): LocalRuntimeMetadata? =
        accessCoordinator.read {
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            if (!metadataFile.isFile) return@read null
            runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }.getOrNull()
        }

    fun bundledOpenCodeVersion(): String = manifestReader.read().openCodeVersion

    














    fun bundledOpenCodeAsset(): LocalRuntimeReleaseAsset? {
        val manifest = runCatching { manifestReader.read() }.getOrNull() ?: return null
        val architecture = runCatching { manifest.architecture(abi) }.getOrNull() ?: return null
        return LocalRuntimeReleaseAsset(
            name = "opencode-linux-$abi-musl.tar.gz",
            url = architecture.openCodeUrl,
            sha256 = architecture.openCodeSha256,
            sizeBytes = 0,
        )
    }

    private fun extractOpenCode(
        archive: File,
        staging: File,
        rootfs: File,
    ): File {
        val extractDir = File(staging, "opencode-extract").apply { mkdirs() }
        try {
            archive.inputStream().use { RuntimeArchive.extractTarGz(it, extractDir) }
            val sourceBinary =
                extractDir.walkTopDown()
                    .firstOrNull { it.isFile && it.name == "opencode" }
                    ?: error("OpenCode archive did not contain the opencode binary")
            val binary = File(rootfs, "usr/local/bin/opencode")
            binary.parentFile?.mkdirs()
            sourceBinary.copyTo(binary, overwrite = true)
            require(binary.setExecutable(true, false) || binary.canExecute()) {
                "Unable to mark OpenCode executable"
            }
            return binary
        } finally {
            extractDir.deleteRecursively()
        }
    }

    private fun carryOverHomeDirectory(
        currentRootfs: File,
        stagingRootfs: File,
    ) {
        val currentHome = File(currentRootfs, "root")
        if (!currentHome.isDirectory) return
        runCatching { currentHome.copyRecursively(File(stagingRootfs, "root"), overwrite = true) }
    }

    






























    private fun unpackBundledPackages(
        onProgress: (Float?, String) -> Unit,
    ): String {
        val alpineArch = apkArchitecture()
        onProgress(null, context.getString(R.string.install_step_installing_runtime_tools))
        
        val destination = localRepositoryDirectory().apply { mkdirs() }
        val archDir = File(destination, alpineArch).apply { mkdirs() }
        File(destination, "noarch").mkdirs()

        val assetPrefix = "runtime/$abi/apk"
        val names = context.assets.list("$assetPrefix/$alpineArch").orEmpty()
        if (names.isEmpty()) {
            
            
            
            
            throw IllegalStateException("安装包缺少内置的软件包仓库，请重新下载完整的安装包")
        }
        var copied = 0
        for (name in names) {
            
            val target = if (name == BUNDLED_INDEX_ASSET) File(archDir, "APKINDEX.tar.gz") else File(archDir, name)
            copyAsset("$assetPrefix/$alpineArch/$name", target)
            copied++
        }
        
        
        
        
        for (name in context.assets.list("$assetPrefix/noarch").orEmpty()) {
            copyAsset("$assetPrefix/noarch/$name", File(destination, "noarch/$name"))
            copied++
        }
        android.util.Log.i(
            LOG_TAG,
            "install: bundled repository unpacked - $copied files, ${megabytes(archDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })} MB",
        )
        localRepositoryFile().apply {
            parentFile?.mkdirs()
            writeText(LOCAL_REPOSITORY_LINE + "\n")
        }
        return LOCAL_REPOSITORY_LINE
    }

    
    private fun copyAsset(
        assetName: String,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        context.assets.open(assetName, AssetManager.ACCESS_STREAMING).use { input ->
            target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        }
    }

    

















    private suspend fun materialize(
        assetName: String,
        destination: File,
        expectedSha256: String,
        startProgress: Float,
        endProgress: Float,
        label: String,
        onProgress: (Float?, String) -> Unit,
    ) {
        if (destination.isFile) {
            runCatching { RuntimeArchive.verifySha256(destination, expectedSha256) }
                .onSuccess {
                    onProgress(endProgress, label)
                    return
                }
            destination.delete()
        }
        destination.parentFile?.mkdirs()
        
        
        var copied = 0L
        var lastReport = 0L
        try {
            context.assets.open(assetName, AssetManager.ACCESS_STREAMING).use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport >= PROGRESS_REPORT_BYTES) {
                            lastReport = copied
                            val declared = input.available().toLong() + copied
                            onProgress(
                                if (declared > 0) {
                                    startProgress + (endProgress - startProgress) * (copied.toFloat() / declared).coerceIn(0f, 1f)
                                } else {
                                    null
                                },
                                label,
                            )
                        }
                    }
                }
            }
        } catch (error: java.io.FileNotFoundException) {
            destination.delete()
            
            throw IllegalStateException("安装包缺少内置运行时文件，请重新下载完整的安装包", error)
        }
        RuntimeArchive.verifySha256(destination, expectedSha256)
        onProgress(endProgress, label)
    }

    











    private fun bundledAssetName(
        version: String,
        abi: String,
        kind: String,
    ): String = "runtime/$abi/$kind-$version-$abi.targz"

    









    private fun apkRepositoryLines(
        mirrors: List<String>,
        repository: String = APK_REPOSITORIES.joinToString(","),
    ): List<String> {
        val branch = alpineBranch()
        return mirrors.flatMap { base -> repository.split(",").map { "$base/$branch/$it" } }
    }

    












    private fun alpineBranch(): String {
        val version = manifestReader.read().alpineVersion.trimStart('v')
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

    









    private fun writeApkRepositories(
        file: File,
        mirrors: List<String>,
    ) {
        
        
        
        
        
        
        
        
        
        val chosen = if (mirrors.isNotEmpty()) mirrors else APK_MIRROR_BASES
        file.apply {
            parentFile?.mkdirs()
            writeText(apkRepositoryLines(chosen.distinct()).joinToString("\n", postfix = "\n"))
        }
    }

    private fun megabytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        
        
        return if (mb < 10.0) String.format(java.util.Locale.ROOT, "%.1f", mb) else String.format(java.util.Locale.ROOT, "%.0f", mb)
    }

    









    private fun localRepositoryDirectory(): File = File(runtimeDirectory, "cache/prefetch")

    private fun localRepositoryFile(): File = File(localRepositoryDirectory(), "repositories")

    

































    private fun installPackages(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        packages: List<String>,
        localRepository: String?,
        onProgress: (Float?, String) -> Unit,
        stepFor: (Int) -> String,
        





        allowNetworkFallback: Boolean,
    ) {
        if (packages.isEmpty()) return
        val apkCache = File(runtimeDirectory, "cache/apk").apply { mkdirs() }
        val lastReported = AtomicReference<String?>(null)
        
        
        
        
        
        
        
        
        
        
        val watcher =
            Thread {
                var lastBytes = 0L
                var lastTickAt = System.nanoTime()
                val phaseStartedAt = System.nanoTime()
                while (!Thread.currentThread().isInterrupted) {
                    val files = runCatching { apkCache.listFiles() }.getOrNull().orEmpty()
                    val bytes = files.sumOf { file -> runCatching { file.length() }.getOrDefault(0L) }
                    val packages = files.count { it.name.endsWith(".apk") }
                    val indexes = files.count { it.name.startsWith("APKINDEX") }
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    val step =
                        installPhaseLabel(
                            allowNetworkFallback = allowNetworkFallback,
                            packages = packages,
                            indexes = indexes,
                            toolsLabelFor = stepFor,
                            resolvingLabel = context.getString(R.string.install_step_resolving_packages),
                            fetchingLabel = context.getString(R.string.install_step_fetching_package_index),
                        )
                    
                    
                    
                    val now = System.nanoTime()
                    val seconds = (now - lastTickAt) / 1_000_000_000.0
                    val perSecond = if (seconds > 0.0) (bytes - lastBytes) / seconds else 0.0
                    
                    
                    val rate = megabytes(perSecond.toLong()) + "/s"
                    val elapsed = ((now - phaseStartedAt) / 1_000_000_000L).toInt()
                    
                    
                    
                    
                    
                    val text =
                        if (allowNetworkFallback) {
                            "$step  ·  ${megabytes(bytes)} MB  ·  $rate  ·  ${elapsed}s"
                        } else {
                            "$step  ·  ${elapsed}s"
                        }
                    if (text != lastReported.getAndSet(text)) onProgress(null, text)
                    lastBytes = bytes
                    lastTickAt = now
                    runCatching { Thread.sleep(1_500) }
                }
            }
        watcher.isDaemon = true
        watcher.start()
        try {
            
            
            
            
            
            
            
            
            val command =
                if (localRepository != null) {
                    "/sbin/apk --cache-dir /var/cache/apk --repositories-file ${LOCAL_REPOSITORY_FILE} " +
                        "add --allow-untrusted ${packages.joinToString(" ")} && " +
                        "/usr/sbin/update-ca-certificates"
                } else {
                    "/sbin/apk --cache-dir /var/cache/apk add ${packages.joinToString(" ")} && " +
                        "/usr/sbin/update-ca-certificates"
                }
            try {
                runGuest(
                    rootfs = rootfs,
                    suite = suite,
                    logName = "tool-install.log",
                    command = command,
                    timeoutMinutes = 15,
                    
                    retryHint =
                        if (localRepository != null) {
                            BUNDLED_REPOSITORY_INSTALL_FAILURE_HINT
                        } else {
                            PACKAGE_INSTALL_RETRY_HINT
                        },
                )
            } catch (packagedFailure: Throwable) {
                
                if (localRepository == null) throw packagedFailure
                
                if (!allowNetworkFallback) throw bundledRepositoryFailure(packagedFailure, packages)
                
                
                
                
                
                android.util.Log.i(
                    LOG_TAG,
                    "install: the local repository was not enough; falling back to fetching from the mirrors\n" +
                        (packagedFailure.message ?: packagedFailure::class.java.simpleName),
                )
                runGuest(
                    rootfs = rootfs,
                    suite = suite,
                    logName = "tool-install-online.log",
                    command =
                        "/sbin/apk --cache-dir /var/cache/apk add ${packages.joinToString(" ")} && " +
                            "/usr/sbin/update-ca-certificates",
                    timeoutMinutes = 15,
                    retryHint = PACKAGE_INSTALL_RETRY_HINT,
                )
            }
        } finally {
            watcher.interrupt()
        }
        onProgress(null, stepFor(0))
    }

    






    private fun bundledRepositoryFailure(
        failure: Throwable,
        requestedPackages: List<String>,
    ): IllegalStateException {
        val missing = missingPackagesFrom(failure.message.orEmpty())
        return IllegalStateException(
            buildString {
                append("内置软件包仓库安装失败")
                if (missing.isNotEmpty()) {
                    append("，缺少：").append(missing.joinToString("、"))
                } else {
                    append("，缺少的包没能从日志里解析出来")
                }
                append("。\n\n")
                
                
                
                
                
                append(
                    "这是安装包本身的问题，不是网络问题，也不是你的操作造成的。\n" +
                        "请把这个错误连同下面的日志报告到项目 issue。\n\n",
                )
                append("请求安装的包共 ${requestedPackages.size} 个。\n\n")
                append(failure.message ?: failure::class.java.simpleName)
            },
            failure,
        )
    }

    








    private fun missingPackagesFrom(message: String): List<String> {
        val names = LinkedHashSet<String>()
        Regex("""([A-Za-z0-9][A-Za-z0-9+._-]*) \(no such package\)""")
            .findAll(message)
            .forEach { names += it.groupValues[1] }
        Regex("""^ERROR:\s+([A-Za-z0-9][A-Za-z0-9+._-]*?)-[0-9][^\s:]*:""", RegexOption.MULTILINE)
            .findAll(message)
            .forEach { names += it.groupValues[1] }
        return names.toList()
    }

    












    private fun runGuest(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        logName: String,
        command: String,
        timeoutMinutes: Long,
        captureOutput: Boolean = false,
        retryHint: String = PACKAGE_INSTALL_RETRY_HINT,
    ): String {
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        val apkCache = File(runtimeDirectory, "cache/apk").apply { mkdirs() }
        
        
        
        
        val localRepository = localRepositoryDirectory().apply { mkdirs() }
        File(rootfs, "var/cache/apk").mkdirs()
        File(rootfs, "var/cache/prefetch").mkdirs()
        val argv =
            buildList {
                add(suite.proot.absolutePath)
                add("--kill-on-exit")
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                add("--link2symlink")
                add("-0")
                add("-r")
                add(rootfs.absolutePath)
                
                
                addAll(SandboxMounts.bindArguments(runtimeDirectory, includeDeviceStorage = false))
                add("-b")
                add("${apkCache.absolutePath}:/var/cache/apk")
                add("-b")
                add("${localRepository.absolutePath}:/var/cache/prefetch")
                add("-w")
                add("/root")
                add("/bin/sh")
                add("-lc")
                add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin $command")
            }
        val installLog =
            File(runtimeDirectory, "logs/$logName").apply {
                parentFile?.mkdirs()
                delete()
            }
        val process =
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(suite.environment())
                    environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                    environment()["TZ"] = SandboxTimeZone.environmentValue(rootfs)
                }
                .start()
        
        
        
        
        val reader =
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { stream ->
                        installLog.bufferedWriter().use { out ->
                            stream.forEachLine { line ->
                                out.write(line)
                                out.newLine()
                                out.flush()
                            }
                        }
                    }
                }
            }
        reader.isDaemon = true
        reader.start()
        val completed = process.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            error("Package installation timed out. $retryHint")
        }
        
        reader.join(5_000)
        require(process.exitValue() == 0) {
            
            
            "Unable to install runtime packages. $retryHint\n\n" +
                "Last log lines:\n${runCatching { installLog.readText() }.getOrDefault("").takeLast(4000)}"
        }
        
        
        return if (captureOutput) runCatching { installLog.readText() }.getOrDefault("") else ""
    }

    











    private fun deviceDnsServers(): List<String> = DeviceDns.servers(context)

    private fun configureRootfs(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        apkMirrors: List<String>,
    ) {
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            setWritable(true, false)
            setExecutable(true, false)
        }
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            
            
            
            writeText(RuntimeGuestConfig.resolvConfContent(deviceDnsServers()))
        }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        File(rootfs, "etc/gitconfig").apply {
            parentFile?.mkdirs()
            writeText(
                "# Written by the Android host. A sandbox is single-user: shared storage reports the\n" +
                    "# app's uid as the owner while the guest runs as root, which git otherwise reads as a\n" +
                    "# repository owned by somebody else.\n" +
                    "[safe]\n" +
                    "\tdirectory = *\n",
            )
        }
        File(rootfs, "etc/profile.d/android-code.sh").apply {
            parentFile?.mkdirs()
            
            writeText(RuntimeGuestConfig.PROFILE_CONTENT)
        }
        File(rootfs, "root/.config/opencode").mkdirs()
        File(rootfs, "root/.local/share/opencode").mkdirs()
        writeApkRepositories(File(rootfs, "etc/apk/repositories"), apkMirrors)
        
        
        val libApk = File(rootfs, "usr/lib/libapk.so.3")
        if (!libApk.exists() && File(rootfs, "usr/lib/libapk.so.3.0.0").isFile) {
            Os.symlink("libapk.so.3.0.0", libApk.absolutePath)
        }
        installAndroidHelperScripts(rootfs)
        require(suite.proot.isFile) { "PRoot launcher is unavailable" }
    }

    



    fun provisionGuestCapabilitiesForExistingInstall() {
        val active = File(runtimeDirectory, "environment")
        listOf(File(active, "rootfs"))
            .filter(File::isDirectory)
            .forEach { rootfs ->
                installAndroidHelperScripts(rootfs)
                removeGuestMcpServers(rootfs)
            }
    }

    







    private fun removeGuestMcpServers(rootfs: File) {
        mergeJsonConfig(File(rootfs, "root/.config/opencode/opencode.json")) { root ->
            root.optJSONObject("mcp")?.let { mcp ->
                REMOVED_MCP_NAMES.forEach(mcp::remove)
                if (mcp.length() == 0) root.remove("mcp") else root.put("mcp", mcp)
            }
        }
        listOf(
            File(rootfs, "root/.claude.json"),
            File(rootfs, "root/.gemini/config/mcp_config.json"),
        ).forEach { file ->
            mergeJsonConfig(file) { root ->
                root.optJSONObject("mcpServers")?.let { servers ->
                    REMOVED_MCP_NAMES.forEach(servers::remove)
                    if (servers.length() == 0) root.remove("mcpServers") else root.put("mcpServers", servers)
                }
            }
        }
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    

    private fun mergeJsonConfig(
        file: File,
        mutate: (JSONObject) -> Unit,
    ) {
        file.parentFile?.mkdirs()
        val root =
            if (file.isFile) {
                runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
            } else {
                JSONObject()
            }
        val before = root.toString()
        mutate(root)
        if (root.toString() != before) {
            file.writeText(root.toString(2) + "\n")
        }
    }

    private fun installAndroidHelperScripts(rootfs: File) {
        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        File(binDir, "android-ui").delete()
        
        listOf(
            "opencode-browser-mcp.py",
            "opencode-schedule-mcp.py",
        ).forEach { File(binDir, it).delete() }
        listOf(
            "android-vision.sh" to "android-vision",
            "android-screenshot.sh" to "android-screenshot",
            "android-instrument.sh" to "android-instrument",
            "android-app.sh" to "android-app",
        ).forEach {
                (assetName, scriptName) ->
            val scriptFile = File(binDir, scriptName)
            context.assets.open("scripts/$assetName").use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
            scriptFile.setExecutable(true, false)
        }
    }

    internal companion object {
        private const val METADATA_FILE = "metadata.json"

        
        private const val COPY_BUFFER_BYTES = 1 shl 20

        
        private const val PROGRESS_REPORT_BYTES = 8L shl 20

        
        private const val BUNDLED_INDEX_ASSET = "APKINDEX.targz"

        
        private const val ALPINE_ASSET = "alpine"

        private const val ENGINE_ASSET = "opencode"

        
        private val BRANCH_PATTERN = Regex("^v\\d+\\.\\d+$")

        







        private val APK_REPOSITORIES = ApkRepositoryDefaults.REPOSITORIES

        
        private const val LOG_TAG = "AndCode.Runtime"

        










        private const val LOCAL_REPOSITORY_FILE = "/var/cache/prefetch/repositories"

        






        private const val LOCAL_REPOSITORY_LINE = "file:///var/cache/prefetch"

        











        private val APK_MIRROR_BASES = ApkRepositoryDefaults.MIRROR_BASES

        
        private val REMOVED_MCP_NAMES = setOf("opencode-browser", "opencode-schedule")

        







        val REQUIRED_RUNTIME_PACKAGES =
            listOf(
                "bash",
                "git",
                "curl",
                "openssh-client",
                "ripgrep",
                "ca-certificates",
                "libstdc++",
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                
                "tzdata",
            )

        
        val OPTIONAL_DEVELOPMENT_PACKAGES =
            listOf(
                "tree",
                "file",
                "less",
                "nano",
                "vim",
                "github-cli",
                "openjdk17",
                "gradle",
                "py3-pip",
                "nodejs",
                "npm",
                "make",
                "cmake",
                "gcc",
                "g++",
                "musl-dev",
                "pkgconf",
                "patch",
                "zip",
                "unzip",
                "sqlite",
                "go",
                "gcompat",
                "util-linux",
            )
    }
}





















internal fun installPhaseLabel(
    allowNetworkFallback: Boolean,
    packages: Int,
    indexes: Int,
    toolsLabelFor: (Int) -> String,
    resolvingLabel: String,
    fetchingLabel: String,
): String =
    when {
        
        packages > 0 -> toolsLabelFor(packages)
        
        !allowNetworkFallback -> toolsLabelFor(0)
        indexes > 0 -> resolvingLabel
        else -> fetchingLabel
    }
