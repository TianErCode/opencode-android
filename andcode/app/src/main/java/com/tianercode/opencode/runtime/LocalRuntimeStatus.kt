package com.tianercode.opencode.runtime

sealed interface LocalRuntimeStatus {
    data object NotInstalled : LocalRuntimeStatus

    











    data object AwaitingStoragePermission : LocalRuntimeStatus

    





    data class Installing(val progress: Float?, val step: String, val agent: LocalAgent? = null) : LocalRuntimeStatus

    data class Stopped(val version: String, val port: Int) : LocalRuntimeStatus

    data class Starting(val version: String, val port: Int) : LocalRuntimeStatus

    data class Updating(
        val currentVersion: String,
        val targetVersion: String,
        val progress: Float?,
        val step: String,
    ) : LocalRuntimeStatus

    data class Ready(val version: String, val port: Int) : LocalRuntimeStatus

    data class Broken(val reason: String) : LocalRuntimeStatus

    data class UnsupportedAbi(val abi: String) : LocalRuntimeStatus
}
