package com.tianercode.opencode.runtime.local

import com.tianercode.opencode.core.storage.DeviceStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File










class SandboxMountsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @After
    fun restoreDeviceStorage() {
        DeviceStorage.install { DeviceStorage.Mounts.None }
    }

    private fun sharedMemoryBind(runtimeDirectory: File): String =
        "${File(runtimeDirectory, "proot-shm").absolutePath}:/dev/shm"

    @Test
    fun `install time binds never include device storage`() {
        val runtime = temp.newFolder("runtime")
        val arguments = SandboxMounts.bindArguments(runtime, includeDeviceStorage = false)
        assertEquals(
            listOf(
                "-b",
                "/dev",
                "-b",
                sharedMemoryBind(runtime),
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "/system",
            ),
            arguments,
        )
    }

    @Test
    fun `shared memory directory is created under the runtime directory`() {
        val runtime = temp.newFolder("runtime")
        val shared = SandboxMounts.sharedMemory(runtime)
        assertEquals(File(runtime, "proot-shm").absolutePath, shared.absolutePath)
        assertTrue("shared memory backing directory must exist", shared.isDirectory)
        
        assertEquals(shared.absolutePath, SandboxMounts.sharedMemory(runtime).absolutePath)
    }

    @Test
    fun `device storage mounts are appended when granted`() {
        val runtime = temp.newFolder("runtime")
        val shared = temp.newFolder("sdcard")
        val volumes = temp.newFolder("volumes")
        DeviceStorage.install { DeviceStorage.Mounts(sharedStorage = shared, volumes = volumes) }

        val arguments = SandboxMounts.bindArguments(runtime, includeDeviceStorage = true)

        assertEquals(
            listOf(
                "-b",
                "/dev",
                "-b",
                sharedMemoryBind(runtime),
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "/system",
                
                "-b",
                "${volumes.absolutePath}:/storage",
                "-b",
                "${shared.absolutePath}:/sdcard",
            ),
            arguments,
        )
    }

    @Test
    fun `a volume that has gone away is skipped rather than passed to proot`() {
        val runtime = temp.newFolder("runtime")
        val shared = temp.newFolder("sdcard")
        val gone = File(temp.root, "gone-card")
        DeviceStorage.install { DeviceStorage.Mounts(sharedStorage = shared, volumes = gone) }

        val arguments = SandboxMounts.bindArguments(runtime, includeDeviceStorage = true)

        assertTrue("the shared storage bind is still there", arguments.contains("${shared.absolutePath}:/sdcard"))
        assertTrue("a missing volume must not be passed to proot", arguments.none { it.contains("gone-card") })
    }

    @Test
    fun `no device storage means the same list as install time`() {
        val runtime = temp.newFolder("runtime")
        DeviceStorage.install { DeviceStorage.Mounts.None }
        assertEquals(
            SandboxMounts.bindArguments(runtime, includeDeviceStorage = false),
            SandboxMounts.bindArguments(runtime, includeDeviceStorage = true),
        )
    }
}
