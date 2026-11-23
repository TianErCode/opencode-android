package com.tianercode.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test











class BackPolicyTest {

    @Test
    fun `rescue terminal is closed before anything else`() {
        assertEquals(
            BackPolicy.Action.CloseRescue,
            BackPolicy.decide(
                rescueOpen = true,
                canGoBackInPage = false,
                atHome = false,
                exitArmed = false,
            ),
        )
    }

    @Test
    fun `a screen below home goes back through page history when it can`() {
        assertEquals(
            BackPolicy.Action.GoBackInPage,
            BackPolicy.decide(
                rescueOpen = false,
                canGoBackInPage = true,
                atHome = false,
                exitArmed = false,
            ),
        )
    }

    @Test
    fun `a screen below home without history only arms the prompt, never exits on one press`() {
        assertEquals(
            BackPolicy.Action.ArmExit,
            BackPolicy.decide(
                rescueOpen = false,
                canGoBackInPage = false,
                atHome = false,
                exitArmed = false,
            ),
        )
    }

    @Test
    fun `an armed exit wins over history so the app is always closable`() {
        assertEquals(
            BackPolicy.Action.Exit,
            BackPolicy.decide(
                rescueOpen = false,
                canGoBackInPage = true,
                atHome = false,
                exitArmed = true,
            ),
        )
    }

    @Test
    fun `home first press arms and the next press exits - however slow the user is`() {
        assertEquals(
            BackPolicy.Action.ArmExit,
            BackPolicy.decide(
                rescueOpen = false,
                canGoBackInPage = false,
                atHome = true,
                exitArmed = false,
            ),
        )
        assertEquals(
            BackPolicy.Action.Exit,
            BackPolicy.decide(
                rescueOpen = false,
                canGoBackInPage = false,
                atHome = true,
                exitArmed = true,
            ),
        )
    }

    @Test
    fun `an armed exit on any screen exits`() {
        assertEquals(
            BackPolicy.Action.Exit,
            BackPolicy.decide(
                rescueOpen = false,
                canGoBackInPage = false,
                atHome = false,
                exitArmed = true,
            ),
        )
    }

    @Test
    fun `only the renderer's own routes count as home`() {
        assertTrue(BackPolicy.isHomePath(null))
        assertTrue(BackPolicy.isHomePath(""))
        assertTrue(BackPolicy.isHomePath("http://127.0.0.1:39678/"))
        assertTrue(BackPolicy.isHomePath("http://127.0.0.1:39678/index.html"))
        assertTrue(BackPolicy.isHomePath("http://127.0.0.1:39678/__boot?t=abc"))
        assertFalse(BackPolicy.isHomePath("http://127.0.0.1:39678/settings"))
        assertFalse(BackPolicy.isHomePath("http://127.0.0.1:39678/settings?tab=general"))
        assertFalse(BackPolicy.isHomePath("http://127.0.0.1:39678/new-session"))
        assertFalse(BackPolicy.isHomePath("http://127.0.0.1:39678/server/local/session/1"))
    }
}
