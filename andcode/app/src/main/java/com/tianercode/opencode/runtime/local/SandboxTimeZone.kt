package com.tianercode.opencode.runtime.local

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.TimeZone
















internal object SandboxTimeZone {
    
    private val ZONE_ID = Regex("^[A-Za-z0-9_+\\-]+(/[A-Za-z0-9_+\\-]+)*$")

    fun deviceZoneId(): String = TimeZone.getDefault().id

    






    fun environmentValue(
        rootfs: File,
        zoneId: String = deviceZoneId(),
    ): String = if (zoneInfo(rootfs, zoneId) != null) zoneId else fixedOffsetValue()

    






    fun apply(
        rootfs: File,
        zoneId: String = deviceZoneId(),
    ) {
        if (zoneInfo(rootfs, zoneId) == null) return
        val localtime = File(rootfs, "etc/localtime")
        runCatching {
            localtime.delete()
            Files.createSymbolicLink(localtime.toPath(), Paths.get("/usr/share/zoneinfo/$zoneId"))
        }
    }

    private fun zoneInfo(
        rootfs: File,
        zoneId: String,
    ): Path? {
        if (!ZONE_ID.matches(zoneId)) return null
        val file = File(rootfs, "usr/share/zoneinfo/$zoneId")
        return if (file.isFile) file.toPath() else null
    }

    





    fun fixedOffsetValue(nowMillis: Long = System.currentTimeMillis()): String {
        val offsetMinutes = TimeZone.getDefault().getOffset(nowMillis) / 60_000
        val sign = if (offsetMinutes >= 0) "-" else "+"
        val total = kotlin.math.abs(offsetMinutes)
        val hours = total / 60
        val minutes = total % 60
        return if (minutes == 0) {
            "UTC$sign$hours"
        } else {
            "UTC$sign$hours:" + minutes.toString().padStart(2, '0')
        }
    }
}
