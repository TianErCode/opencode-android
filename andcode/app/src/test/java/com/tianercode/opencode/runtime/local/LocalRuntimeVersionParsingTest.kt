package com.tianercode.opencode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test










class LocalRuntimeVersionParsingTest {
    @Test
    fun `equal versions compare as zero`() {
        assertEquals(0, compareRuntimeVersions("2.0.32", "2.0.32"))
        assertEquals(0, compareRuntimeVersions("2026.10.02.1", "2026.10.02.1"))
    }

    @Test
    fun `comparison is numeric per component, not lexicographic`() {
        assertTrue(compareRuntimeVersions("2.0.9", "2.0.32") < 0)
        assertTrue(compareRuntimeVersions("2.0.32", "2.0.9") > 0)
        assertTrue(compareRuntimeVersions("2.0.99", "2.0.100") < 0)
    }

    @Test
    fun `major and minor wins over patch`() {
        assertTrue(compareRuntimeVersions("2.0.99", "2.1.0") < 0)
        assertTrue(compareRuntimeVersions("2.10.0", "3.0.0") < 0)
    }

    @Test
    fun `a missing component counts as zero`() {
        assertEquals(0, compareRuntimeVersions("2.0.32", "2.0.32.0"))
        assertEquals(0, compareRuntimeVersions("2.0", "2.0.0.0"))
        assertTrue(compareRuntimeVersions("2.0.32.1", "2.0.32") > 0)
    }

    @Test
    fun `normalization drops the v prefix, prerelease and build metadata`() {
        assertEquals("2.0.32", normalizeRuntimeVersion("v2.0.32"))
        assertEquals("2.0.32", normalizeRuntimeVersion("2.0.32-beta.1"))
        assertEquals("2.0.32", normalizeRuntimeVersion("2.0.32+build.7"))
        assertEquals("2.0.32", normalizeRuntimeVersion("  v2.0.32-beta+sha  "))
    }

    @Test
    fun `an invalid version is rejected rather than silently compared`() {
        
        listOf("", "abc", "1", "2.0.x", "2.0.32.1.2", "-2.0.32", "v", "2..32").forEach { invalid ->
            try {
                compareRuntimeVersions(invalid, "2.0.32")
                fail("invalid version must throw: '$invalid'")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("Invalid OpenCode version"))
            }
        }
    }

    @Test
    fun `comparison is antisymmetric`() {
        val pairs =
            listOf(
                "2.0.32" to "2.0.33",
                "2.0.32" to "2.0.32.1",
                "2.1.0" to "2.0.99",
                "v2.0.32" to "2.0.32-beta",
            )
        pairs.forEach { (left, right) ->
            assertEquals(
                "compare($left,$right) must be the negation of compare($right,$left)",
                Integer.signum(compareRuntimeVersions(left, right)),
                -Integer.signum(compareRuntimeVersions(right, left)),
            )
        }
    }
}
