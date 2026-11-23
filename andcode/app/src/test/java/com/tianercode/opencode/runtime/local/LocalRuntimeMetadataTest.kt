package com.tianercode.opencode.runtime.local

import com.tianercode.opencode.runtime.LocalAgent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File















class LocalRuntimeMetadataTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @Test
    fun `legacy metadata without the new fields decodes to the documented defaults`() {
        val legacyPayload = """{"version":"2.0.32","port":4097,"installedAt":1700000000000}"""
        val metadata = json.decodeFromString<LocalRuntimeMetadata>(legacyPayload)

        assertEquals("2.0.32", metadata.version)
        assertEquals(4097, metadata.port)
        assertEquals(1_700_000_000_000L, metadata.installedAt)
        assertEquals("legacy", metadata.runtimeVersion)
        assertEquals("unknown", metadata.abi)
        assertEquals(setOf(LocalAgent.OPEN_CODE.id), metadata.components)
        assertTrue("legacy runtimes did have the full Alpine toolchain", metadata.hasFullDevelopmentTools())
    }

    @Test
    fun `the current shape round-trips through the same serializer the app uses`() {
        val metadata =
            LocalRuntimeMetadata(
                version = "2.0.32",
                port = 4097,
                installedAt = 42L,
                runtimeVersion = "2026.10.02.1",
                abi = "x86_64",
                components = setOf(LocalAgent.OPEN_CODE.id, "claude"),
                fullDevelopmentToolsInstalled = false,
            )
        val decoded = json.decodeFromString<LocalRuntimeMetadata>(json.encodeToString(LocalRuntimeMetadata.serializer(), metadata))
        assertEquals(metadata, decoded)
        assertFalse(decoded.hasFullDevelopmentTools())
    }

    @Test
    fun `agents can be added and removed by id without touching anything else`() {
        
        val base = metadata().copy(components = emptySet())
        val withAgent = base.with(LocalAgent.OPEN_CODE)
        assertTrue(withAgent.has(LocalAgent.OPEN_CODE))
        assertEquals(base.version, withAgent.version)
        assertEquals(base.runtimeVersion, withAgent.runtimeVersion)

        val withoutAgent = withAgent.without(LocalAgent.OPEN_CODE)
        assertFalse(withoutAgent.has(LocalAgent.OPEN_CODE))
        assertEquals(base.components, withoutAgent.components)
    }

    @Test
    fun `a decoded legacy runtime is what triggers the one-time rebuild`() {
        val legacy = json.decodeFromString<LocalRuntimeMetadata>("""{"version":"2.0.32","port":4097,"installedAt":0}""")
        val manager =
            LocalRuntimeManager(
                runtimeDirectory = File(System.getProperty("java.io.tmpdir"), "t6-metadata-${System.nanoTime()}"),
                abi = "x86_64",
                bundledRuntimeVersion = { "2026.10.02.1" },
            )
        assertTrue(
            "an old install must be rebuilt exactly once when the APK's runtimeVersion is newer",
            manager.runtimeVersionNeedsReprovision(legacy),
        )
    }

    private fun metadata(): LocalRuntimeMetadata =
        LocalRuntimeMetadata(
            version = "2.0.32",
            port = 4097,
            installedAt = 0L,
            runtimeVersion = "2026.10.02.1",
            abi = "x86_64",
            fullDevelopmentToolsInstalled = false,
        )
}
