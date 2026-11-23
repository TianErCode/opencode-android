package com.tianercode.opencode.runtime.local

import com.tianercode.opencode.core.storage.DeviceStorage
import java.io.File

















internal object SandboxMounts {
    private const val GUEST_SHARED_MEMORY = "/dev/shm"

    
    fun sharedMemory(runtimeDirectory: File): File =
        File(runtimeDirectory, "proot-shm").apply {
            mkdirs()
            
            
            
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }

    









    fun bindArguments(
        runtimeDirectory: File,
        includeDeviceStorage: Boolean = true,
    ): List<String> =
        buildList {
            add("-b")
            add("/dev")
            add("-b")
            add("${sharedMemory(runtimeDirectory).absolutePath}:$GUEST_SHARED_MEMORY")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("/system")
            if (includeDeviceStorage) addAll(DeviceStorage.bindArguments())
        }
}
