package com.tianercode.opencode.startup

import com.tianercode.opencode.runtime.LocalRuntimeStatus

internal const val LOCAL_RUNTIME_ID = "local-android"


internal enum class RuntimeAutoStartTrigger {
    




    AppLaunch,

    




    BootOrPackageReplaced,
}










internal fun shouldAutoStartLocalRuntime(
    onboardingCompleted: Boolean,
    localRuntimeStatus: LocalRuntimeStatus,
    selectedRuntimeId: String?,
    trigger: RuntimeAutoStartTrigger,
    localRuntimeIdleStopEnabled: Boolean,
): Boolean =
    onboardingCompleted &&
        localRuntimeStatus !is LocalRuntimeStatus.NotInstalled &&
        (selectedRuntimeId == null || selectedRuntimeId == LOCAL_RUNTIME_ID) &&
        (trigger == RuntimeAutoStartTrigger.AppLaunch || !localRuntimeIdleStopEnabled)
























internal fun shouldRestoreOnForegroundReturn(
    status: LocalRuntimeStatus,
    idleStopInProgress: Boolean,
    userStoppedRuntime: Boolean,
): Boolean = !userStoppedRuntime && (status is LocalRuntimeStatus.Stopped || idleStopInProgress)
