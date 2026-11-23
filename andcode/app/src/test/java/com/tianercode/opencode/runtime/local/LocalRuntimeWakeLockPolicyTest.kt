package com.tianercode.opencode.runtime.local

import com.tianercode.opencode.runtime.LocalRuntimeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test






class LocalRuntimeWakeLockPolicyTest {
    @Test
    fun readyEngineDoesNotHoldCpuAwakeEvenWhenWorkIsReported() {
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Ready("2.3.0", 4096), hasActiveWork = true))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Ready("2.3.0", 4096), hasActiveWork = false))
    }

    @Test
    fun boundedOperationsHoldCpuAwake() {
        assertTrue(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Installing(null, "install"), hasActiveWork = false))
        assertTrue(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Starting("2.3.0", 4096), hasActiveWork = false))
        assertTrue(
            localRuntimeNeedsWakeLock(
                LocalRuntimeStatus.Updating("2.3.0", "2.3.1", null, "download"),
                hasActiveWork = false,
            ),
        )
    }

    @Test
    fun inactiveStatesHoldNothing() {
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.NotInstalled, hasActiveWork = false))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Stopped("2.3.0", 4096), hasActiveWork = false))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.Broken("missing"), hasActiveWork = false))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.UnsupportedAbi("x86"), hasActiveWork = false))
        assertFalse(localRuntimeNeedsWakeLock(LocalRuntimeStatus.AwaitingStoragePermission, hasActiveWork = false))
    }
}
