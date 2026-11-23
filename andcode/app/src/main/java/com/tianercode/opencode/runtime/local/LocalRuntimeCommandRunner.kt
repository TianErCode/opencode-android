package com.tianercode.opencode.runtime.local

import java.io.File
import java.util.concurrent.TimeUnit

class LocalRuntimeCommandRunner(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val timeoutSeconds: Long = 15L,
    private val maxOutputCharacters: Int = 4_000,
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
) {
    init {
        require(timeoutSeconds > 0)
        require(maxOutputCharacters > 0)
    }

    fun run(definition: LocalRuntimeToolDefinition): LocalRuntimeCommandResult = runShell(definition.command)

    @Synchronized
    fun runShell(
        commandText: String,
        timeoutSeconds: Long = this.timeoutSeconds,
    ): LocalRuntimeCommandResult =
        accessCoordinator.read {
            require(timeoutSeconds > 0L)
            val runtime =
                installedRuntimeProvider()
                    ?: return@read LocalRuntimeCommandResult(127, messages.notInstalled)
            val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
            val guestTimeZone = SandboxTimeZone.environmentValue(runtime.rootfs)
            SandboxTimeZone.apply(runtime.rootfs)
            val outputFile = File.createTempFile("diagnostic-", ".log", File(runtimeDirectory, "logs").apply { mkdirs() })
            try {
                val command =
                    buildList {
                        add(runtime.commandSuite.proot.absolutePath)
                        add("--kill-on-exit")
                        
                        
                        
                        add("-0")
                        add("-r")
                        add(runtime.rootfs.absolutePath)
                        addAll(SandboxMounts.bindArguments(runtimeDirectory))
                        add("-w")
                        add("/root")
                        add("/bin/sh")
                        add("-lc")
                        add(commandText)
                    }
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                        .apply {
                            environment().clear()
                            environment().putAll(
                                localRuntimeEnvironment(
                                    runtime.commandSuite.environment(),
                                    prootTmp,
                                    timeZone = guestTimeZone,
                                ),
                            )
                        }
                        .start()
                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                    LocalRuntimeCommandResult(124, messages.commandTimedOut)
                } else {
                    LocalRuntimeCommandResult(
                        exitCode = process.exitValue(),
                        output = outputFile.readText().takeLast(maxOutputCharacters),
                    )
                }
            } finally {
                outputFile.delete()
            }
        }
}
