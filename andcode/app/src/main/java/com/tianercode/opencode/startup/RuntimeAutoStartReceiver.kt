package com.tianercode.opencode.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tianercode.opencode.OpenCodeApplication


class RuntimeAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!isRuntimeAutoStartBroadcast(intent.action)) return
        val app = context.applicationContext as? OpenCodeApplication ?: return
        RuntimeAutoStartInitializer.restoreIfConfigured(app, RuntimeAutoStartTrigger.BootOrPackageReplaced)
    }
}

internal fun isRuntimeAutoStartBroadcast(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
