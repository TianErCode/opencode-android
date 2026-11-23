package com.tianercode.opencode.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File










class DeviceStorageAccess(
    private val context: Context,
) {
    fun isGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    
    fun needsSettingsScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    
    fun runtimePermissions(): Array<String> =
        if (needsSettingsScreen()) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    





    fun settingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )

    fun settingsFallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    




    fun mounts(): DeviceStorage.Mounts {
        if (!isGranted()) return DeviceStorage.Mounts.None
        val shared = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        val volumes = File("/storage")
        return DeviceStorage.Mounts(
            sharedStorage = shared?.takeIf { it.isDirectory },
            volumes = volumes.takeIf { it.isDirectory },
        )
    }
}
