package com.tianercode.opencode.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File











class LocalRuntimeManagerVersionCheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun manager(bundled: String?): LocalRuntimeManager =
        LocalRuntimeManager(
            
            runtimeDirectory = temp.newFolder(),
            abi = "x86_64",
            bundledRuntimeVersion = { bundled },
        )

    private fun metadata(runtimeVersion: String): LocalRuntimeMetadata =
        LocalRuntimeMetadata(
            version = "2.0.32",
            port = 4097,
            installedAt = 0L,
            runtimeVersion = runtimeVersion,
            abi = "x86_64",
        )

    @Test
    fun `same runtime version does not trigger a reinstall`() {
        assertFalse(manager("2026.10.02.1").runtimeVersionNeedsReprovision(metadata("2026.10.02.1")))
    }

    @Test
    fun `a different runtime version triggers a reinstall`() {
        assertTrue(manager("2026.10.02.2").runtimeVersionNeedsReprovision(metadata("2026.10.02.1")))
    }

    @Test
    fun `a runtime installed before the field existed is rebuilt once`() {
        
        assertTrue(manager("2026.10.02.1").runtimeVersionNeedsReprovision(metadata("legacy")))
    }

    @Test
    fun `a runtime newer than the APK is rebuilt too`() {
        
        assertTrue(manager("2026.10.02.1").runtimeVersionNeedsReprovision(metadata("2026.10.02.9")))
    }

    @Test
    fun `an unreadable bundled manifest does not trigger a reinstall`() {
        assertFalse(manager(null).runtimeVersionNeedsReprovision(metadata("2026.10.02.1")))
    }

    @Test
    fun `a blank bundled version does not trigger a reinstall`() {
        assertFalse(manager("   ").runtimeVersionNeedsReprovision(metadata("2026.10.02.1")))
    }

    @Test
    fun `a missing manager configuration keeps the old behaviour`() {
        
        val unconfigured = LocalRuntimeManager(runtimeDirectory = File(temp.root, "runtime2"), abi = "x86_64")
        assertFalse(unconfigured.runtimeVersionNeedsReprovision(metadata("2026.10.02.1")))
    }

    @Test
    fun `a bundled version provider that throws is treated as unreadable`() {
        
        
        val throwing =
            LocalRuntimeManager(
                runtimeDirectory = File(temp.root, "runtime3"),
                abi = "x86_64",
                bundledRuntimeVersion = { error("asset read failed") },
            )
        assertFalse(throwing.runtimeVersionNeedsReprovision(metadata("2026.10.02.1")))
    }

    @Test
    fun `a blank installed runtime version triggers a reinstall`() {
        assertTrue(manager("2026.10.02.1").runtimeVersionNeedsReprovision(metadata("")))
    }

    @Test
    fun `comparison is exact and does not trim either side`() {
        
        
        assertTrue(manager("2026.10.02.1").runtimeVersionNeedsReprovision(metadata(" 2026.10.02.1 ")))
        assertTrue(manager(" 2026.10.02.1 ").runtimeVersionNeedsReprovision(metadata("2026.10.02.1")))
    }
}
