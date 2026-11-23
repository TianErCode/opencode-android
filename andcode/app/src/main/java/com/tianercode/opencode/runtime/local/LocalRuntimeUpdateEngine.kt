package com.tianercode.opencode.runtime.local

interface LocalRuntimeUpdateEngine {
    suspend fun prepare(
        release: LocalRuntimeRelease,
        onProgress: (Float?, String) -> Unit,
    ): PreparedRuntimeUpdate

    suspend fun activate(prepared: PreparedRuntimeUpdate): LocalRuntimeMetadata

    suspend fun commit()

    suspend fun recover(): LocalRuntimeMetadata?

    suspend fun rollback(): LocalRuntimeMetadata

    suspend fun rollbackVersion(): String?
}

class DefaultLocalRuntimeUpdateEngine(
    private val releaseClient: LocalRuntimeReleaseClient,
    private val updater: LocalRuntimeUpdater,
) : LocalRuntimeUpdateEngine {
    override suspend fun prepare(
        release: LocalRuntimeRelease,
        onProgress: (Float?, String) -> Unit,
    ): PreparedRuntimeUpdate = updater.prepare(release, onProgress)

    override suspend fun activate(prepared: PreparedRuntimeUpdate): LocalRuntimeMetadata = updater.activate(prepared)

    override suspend fun commit() = updater.commitActivation()

    override suspend fun recover(): LocalRuntimeMetadata? = updater.recoverInterruptedActivation()

    override suspend fun rollback(): LocalRuntimeMetadata = updater.rollback()

    override suspend fun rollbackVersion(): String? = updater.rollbackVersion()
}

interface LocalRuntimeOperations {
    fun currentMetadata(): LocalRuntimeMetadata?

    suspend fun stop()

    suspend fun start(): com.tianercode.opencode.runtime.LocalRuntimeStatus.Ready
}
