package com.tianercode.opencode.runtime.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.SimpleTimeZone
import java.util.TimeZone









class SandboxTimeZoneTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun saveTimeZone() {
        originalTimeZone = TimeZone.getDefault()
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun rootfs(): File = temp.newFolder("rootfs").apply { File(this, "usr/share/zoneinfo").mkdirs() }

    private fun writeZoneInfo(
        rootfs: File,
        zoneId: String,
    ) {
        val file = File(rootfs, "usr/share/zoneinfo/$zoneId")
        file.parentFile?.mkdirs()
        file.writeText("TZif fake")
    }

    

    @Test
    fun `device zone id is the default zone id`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals("Asia/Shanghai", SandboxTimeZone.deviceZoneId())
    }

    

    @Test
    fun `a named zone is used only when its zoneinfo file exists`() {
        val rootfs = rootfs()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        writeZoneInfo(rootfs, "Asia/Shanghai")
        assertEquals("Asia/Shanghai", SandboxTimeZone.environmentValue(rootfs, "Asia/Shanghai"))
    }

    @Test
    fun `a missing zoneinfo file falls back to the fixed offset`() {
        val rootfs = rootfs()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("UTC-0", SandboxTimeZone.environmentValue(rootfs, "Asia/Shanghai"))
    }

    @Test
    fun `an invalid zone id can never reach the filesystem`() {
        
        val rootfs = rootfs()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val rejected =
            listOf(
                "../etc/passwd",
                "/etc/passwd",
                "Asia/../../../etc/passwd",
                "Asia/Shang hai",
                "Asia/\u0000x",
                "",
                "..",
            )
        rejected.forEach { zoneId ->
            assertEquals("zone id must be refused: '$zoneId'", "UTC-0", SandboxTimeZone.environmentValue(rootfs, zoneId))
        }
    }

    @Test
    fun `a zone id with dots is refused even if the file would exist`() {
        
        val rootfs = rootfs()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        writeZoneInfo(rootfs, "Asia/Shanghai.v2")
        assertEquals("UTC-0", SandboxTimeZone.environmentValue(rootfs, "Asia/Shanghai.v2"))
    }

    @Test
    fun `a posix timezone environment value is a valid fixed-offset string`() {
        val rootfs = rootfs()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val value = SandboxTimeZone.environmentValue(rootfs, "Asia/Shanghai")
        assertTrue("unexpected TZ value: $value", Regex("^UTC[+-]\\d+(:\\d{2})?$").matches(value))
        assertEquals("UTC-8", value)
    }

    

    @Test
    fun `whole hour offsets keep the posix inverted sign`() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
        assertEquals("UTC-8", SandboxTimeZone.fixedOffsetValue(0L))
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"))
        assertEquals("UTC+5", SandboxTimeZone.fixedOffsetValue(0L))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        
        assertEquals("UTC-0", SandboxTimeZone.fixedOffsetValue(0L))
    }

    @Test
    fun `fractional hour offsets keep minutes`() {
        TimeZone.setDefault(SimpleTimeZone((5 * 60 + 30) * 60_000, "test+5:30"))
        assertEquals("UTC-5:30", SandboxTimeZone.fixedOffsetValue(0L))
        TimeZone.setDefault(SimpleTimeZone(-(3 * 60 + 45) * 60_000, "test-3:45"))
        assertEquals("UTC+3:45", SandboxTimeZone.fixedOffsetValue(0L))
    }

    @Test
    fun `daylight saving is included in the fixed offset`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        
        assertEquals("UTC+4", SandboxTimeZone.fixedOffsetValue(Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()))
        assertEquals("UTC+5", SandboxTimeZone.fixedOffsetValue(Instant.parse("2026-01-15T00:00:00Z").toEpochMilli()))
    }

    

    @Test
    fun `apply does nothing when the zoneinfo file is missing`() {
        val rootfs = rootfs()
        SandboxTimeZone.apply(rootfs, "Asia/Shanghai")
        assertFalse(File(rootfs, "etc/localtime").exists())
    }

    @Test
    fun `apply leaves an existing localtime alone when the zone is invalid`() {
        val rootfs = rootfs()
        val localtime = File(rootfs, "etc/localtime")
        localtime.parentFile?.mkdirs()
        localtime.writeText("regular file from an older install")
        SandboxTimeZone.apply(rootfs, "../etc/passwd")
        assertTrue("a refused zone must not touch /etc/localtime", localtime.isFile)
        assertEquals("regular file from an older install", localtime.readText())
    }

    @Test
    fun `apply never throws even when symlinks are unavailable`() {
        
        
        val rootfs = rootfs()
        writeZoneInfo(rootfs, "Asia/Shanghai")
        runCatching { SandboxTimeZone.apply(rootfs, "Asia/Shanghai") }
            .onFailure { error -> throw AssertionError("apply must not throw", error) }

        val localtime = File(rootfs, "etc/localtime")
        val path = localtime.toPath()
        if (Files.isSymbolicLink(path)) {
            assertEquals(Paths.get("/usr/share/zoneinfo/Asia/Shanghai"), Files.readSymbolicLink(path))
        } else {
            
            assertFalse("no half-written /etc/localtime", localtime.isFile)
        }
    }
}
