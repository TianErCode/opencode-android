package com.tianercode.opencode.runtime.local

import android.content.Context
import java.io.File
import org.json.JSONObject
































object LocalRuntimeServiceCredential {
    private const val RELATIVE_PATH = "runtime/environment/rootfs/root/.local/state/opencode/service.json"

    
    fun password(context: Context): String? {
        val file = File(context.filesDir, RELATIVE_PATH)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()).optString("password").trim().ifEmpty { null } }
            .getOrNull()
    }
}