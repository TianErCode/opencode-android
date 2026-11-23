package com.tianercode.opencode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File














class RuntimeGuestConfigTest {
    @get:Rule
    val temp = TemporaryFolder()

    
    private fun rootfs(alpineRelease: String? = "3.24.1\n"): File {
        val rootfs = temp.newFolder("rootfs")
        File(rootfs, "etc/apk").mkdirs()
        if (alpineRelease != null) File(rootfs, "etc/alpine-release").writeText(alpineRelease)
        return rootfs
    }

    



    private fun assertNotTouched(file: File) {
        val before = file.lastModified()
        assertEquals("file was rewritten: ${file.absolutePath}", OLD_TIMESTAMP, before)
    }

    private fun markOld(file: File) {
        assertTrue("could not set lastModified on ${file.absolutePath}", file.setLastModified(OLD_TIMESTAMP))
    }

    

    @Test
    fun `gitconfig is created with the wildcard when missing`() {
        val rootfs = rootfs()
        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val text = File(rootfs, "etc/gitconfig").readText()
        assertTrue("missing [safe] section:\n$text", text.contains("[safe]"))
        assertTrue("missing the wildcard safe.directory:\n$text", text.contains("directory = *"))
    }

    @Test
    fun `gitconfig that already grants every directory is not rewritten`() {
        val rootfs = rootfs()
        
        val file = File(rootfs, "etc/gitconfig")
        file.writeText(
            "# Written by the Android host. A sandbox is single-user.\n" +
                "[safe]\n" +
                "\tdirectory = *\n",
        )
        markOld(file)
        RuntimeGuestConfig.ensureUpToDate(rootfs)
        assertNotTouched(file)
    }

    @Test
    fun `user configuration in gitconfig is preserved and only the missing key is appended`() {
        val rootfs = rootfs()
        val file = File(rootfs, "etc/gitconfig")
        file.writeText("[user]\n\tname = someone\n\temail = someone@example.com\n")
        markOld(file)

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val text = file.readText()
        assertTrue("user section was lost:\n$text", text.contains("name = someone"))
        assertTrue("user section was lost:\n$text", text.contains("email = someone@example.com"))
        assertTrue("wildcard was not appended:\n$text", text.contains("[safe]") && text.contains("directory = *"))
        
        assertTrue("user's original content is not a prefix of the file:\n$text", text.startsWith("[user]\n\tname = someone\n\temail = someone@example.com\n"))
    }

    @Test
    fun `a directory key in another section is not mistaken for safe directory`() {
        val rootfs = rootfs()
        val file = File(rootfs, "etc/gitconfig")
        file.writeText("[core]\n\tdirectory = *\n")
        markOld(file)

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val text = file.readText()
        assertTrue("core.directory was mistaken for safe.directory:\n$text", text.contains("[safe]"))
        assertTrue("user's [core] section was lost:\n$text", text.contains("[core]"))
    }

    @Test
    fun `gitconfig is only appended to once`() {
        val rootfs = rootfs()
        RuntimeGuestConfig.ensureUpToDate(rootfs)
        val file = File(rootfs, "etc/gitconfig")
        val first = file.readText()

        RuntimeGuestConfig.ensureUpToDate(rootfs)
        RuntimeGuestConfig.ensureUpToDate(rootfs)

        assertEquals("the appended block was added more than once", first, file.readText())
    }

    

    @Test
    fun `repositories file is created with every mirror line when missing`() {
        val rootfs = rootfs()

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val lines = File(rootfs, "etc/apk/repositories").readLines()
        
        assertEquals(16, lines.size)
        assertEquals(16, lines.toSet().size)
        assertTrue(lines.all { it.endsWith("/v3.24/main") || it.endsWith("/v3.24/community") })
        
        assertTrue(lines.all { !it.contains("/v3/") })
    }

    @Test
    fun `an already complete repositories file is not rewritten`() {
        val rootfs = rootfs()
        RuntimeGuestConfig.ensureUpToDate(rootfs)
        val file = File(rootfs, "etc/apk/repositories")
        markOld(file)

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        assertNotTouched(file)
    }

    @Test
    fun `the old single mirror line is kept and the rest are added exactly once`() {
        val rootfs = rootfs()
        val file = File(rootfs, "etc/apk/repositories")
        file.writeText("https://mirrors.ustc.edu.cn/alpine/v3.24/main\n")

        RuntimeGuestConfig.ensureUpToDate(rootfs)
        val first = file.readText()
        assertEquals(16, first.lines().count(String::isNotBlank))
        assertEquals(1, first.lines().count { it == "https://mirrors.ustc.edu.cn/alpine/v3.24/main" })

        RuntimeGuestConfig.ensureUpToDate(rootfs)
        assertEquals("second run changed the file", first, file.readText())
    }

    @Test
    fun `a user added repository line is preserved`() {
        val rootfs = rootfs()
        val file = File(rootfs, "etc/apk/repositories")
        val custom = "http://192.168.1.10/alpine/v3.24/main"
        file.writeText(custom + "\n")

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val lines = file.readLines()
        assertTrue("the user's mirror line was removed", lines.contains(custom))
        assertEquals(17, lines.count(String::isNotBlank))
    }

    @Test
    fun `branch falls back to the repositories file when alpine-release is gone`() {
        val rootfs = rootfs(alpineRelease = null)
        val file = File(rootfs, "etc/apk/repositories")
        file.writeText("https://mirrors.ustc.edu.cn/alpine/v3.23/main\n")

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val lines = file.readLines()
        assertEquals(16, lines.count(String::isNotBlank))
        assertTrue(lines.all { it.contains("/v3.23/") })
    }

    @Test
    fun `nothing is written when the branch cannot be determined`() {
        val rootfs = rootfs(alpineRelease = null)
        val file = File(rootfs, "etc/apk/repositories")

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        assertFalse("guessed a branch with no evidence available", file.exists())
    }

    @Test
    fun `ensureUpToDate on a missing rootfs is a no-op`() {
        val missing = File(temp.root, "does-not-exist")
        RuntimeGuestConfig.ensureUpToDate(missing)
        assertFalse(missing.exists())
    }

    private companion object {
        
        const val OLD_TIMESTAMP = 1_000_000_000_000L
    }
}
