package com.tianercode.opencode.startup

import com.tianercode.opencode.OpenCodeApplication
import com.tianercode.opencode.runtime.LocalRuntimeStatus












internal object RuntimeAutoStartInitializer {
    



















    fun restoreIfConfigured(
        app: OpenCodeApplication,
        trigger: RuntimeAutoStartTrigger,
    ): Boolean {
        val status = app.localRuntimeManager.status()
        if (status is LocalRuntimeStatus.NotInstalled) {
            app.localRuntimeController.installAndStart()
            return true
        }
        if (
            !shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = status,
                selectedRuntimeId = null,
                trigger = trigger,
                localRuntimeIdleStopEnabled = app.settings.localRuntimeIdleStopEnabled,
            )
        ) {
            return false
        }

        app.localRuntimeController.start()
        return true
    }

    





    fun startIfInstalled(app: OpenCodeApplication): Boolean {
        
        
        if (!app.hasInstalledRuntime()) {
            app.localRuntimeController.installAndStart()
            return true
        }
        app.localRuntimeController.start()
        return true
    }
}
