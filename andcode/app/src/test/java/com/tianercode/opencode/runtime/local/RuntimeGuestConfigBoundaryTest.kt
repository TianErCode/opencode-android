package com.tianercode.opencode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File















class RuntimeGuestConfigBoundaryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun rootfs(alpineRelease: String? = "3.24.1\n"): File {
        val rootfs = temp.newFolder("rootfs")
        File(rootfs, "etc/apk").mkdirs()
        if (alpineRelease != null) File(rootfs, "etc/alpine-release").writeText(alpineRelease)
        return rootfs
    }

    private fun repositoryLines(branch: String): List<String> =
        ApkRepositoryDefaults.MIRROR_BASES.flatMap { base ->
            ApkRepositoryDefaults.REPOSITORIES.map { repository -> "$base/$branch/$repository" }
        }

    private fun markOld(file: File) {
        assertTrue(file.setLastModified(OLD_TIMESTAMP))
    }

    private fun assertNotTouched(file: File) {
        assertEquals("file was rewritten: ${file.absolutePath}", OLD_TIMESTAMP, file.lastModified())
    }

    private fun waitForDistinctTimestamp() {
        
    }

    

    @Test
    fun `profile is created with the canonical environment`() {
        val rootfs = rootfs()
        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val profile = File(rootfs, "etc/profile.d/android-code.sh")
        assertEquals(RuntimeGuestConfig.PROFILE_CONTENT, profile.readText())
        assertTrue(profile.readText().contains("export JAVA_HOME=/usr/lib/jvm/java-17-openjdk"))
        assertTrue(profile.readText().contains("export OPENCODE_CONFIG_DIR=/root/.config/opencode"))
    }

    @Test
    fun `a profile that already matches is not rewritten`() {
        val rootfs = rootfs()
        val profile = File(rootfs, "etc/profile.d/android-code.sh")
        profile.parentFile?.mkdirs()
        profile.writeText(RuntimeGuestConfig.PROFILE_CONTENT)
        markOld(profile)

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        assertNotTouched(profile)
    }

    @Test
    fun `a user modified profile is restored to the canonical content`() {
        
        
        val rootfs = rootfs()
        val profile = File(rootfs, "etc/profile.d/android-code.sh")
        profile.parentFile?.mkdirs()
        profile.writeText("export PATH=/broken\n")

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        assertEquals(RuntimeGuestConfig.PROFILE_CONTENT, profile.readText())
    }

    

    @Test
    fun `resolv conf is not touched when the device dns could not be read`() {
        val rootfs = rootfs()
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeText("nameserver 10.0.0.1\n")
        markOld(resolv)

        RuntimeGuestConfig.ensureUpToDate(rootfs, dnsServers = emptyList())

        assertEquals("nameserver 10.0.0.1\n", resolv.readText())
        assertNotTouched(resolv)
    }

    @Test
    fun `resolv conf lists the device resolvers first and public fallbacks after`() {
        val rootfs = rootfs()
        RuntimeGuestConfig.ensureUpToDate(rootfs, dnsServers = listOf("192.168.1.1", "10.0.0.53"))

        val resolv = File(rootfs, "etc/resolv.conf")
        assertEquals(RuntimeGuestConfig.resolvConfContent(listOf("192.168.1.1", "10.0.0.53")), resolv.readText())
        val lines = resolv.readLines()
        assertEquals("nameserver 192.168.1.1", lines[0])
        assertEquals("nameserver 10.0.0.53", lines[1])
        assertEquals("nameserver 223.5.5.5", lines[2])
        assertEquals("nameserver 8.8.8.8", lines.last())
    }

    @Test
    fun `resolv conf is rewritten when the network changed but not when it did not`() {
        val rootfs = rootfs()
        RuntimeGuestConfig.ensureUpToDate(rootfs, dnsServers = listOf("192.168.1.1"))
        val resolv = File(rootfs, "etc/resolv.conf")

        markOld(resolv)
        RuntimeGuestConfig.ensureUpToDate(rootfs, dnsServers = listOf("192.168.1.1"))
        assertNotTouched(resolv)

        RuntimeGuestConfig.ensureUpToDate(rootfs, dnsServers = listOf("192.168.1.2"))
        assertTrue(resolv.readText().startsWith("nameserver 192.168.1.2\n"))
    }

    

    @Test
    fun `an uppercase SAFE section is recognised`() {
        val rootfs = rootfs()
        val gitconfig = File(rootfs, "etc/gitconfig")
        gitconfig.parentFile?.mkdirs()
        gitconfig.writeText("[SAFE]\n\tdirectory = *\n")
        markOld(gitconfig)

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        assertNotTouched(gitconfig)
    }

    @Test
    fun `a directory value without spaces is recognised`() {
        val rootfs = rootfs()
        val gitconfig = File(rootfs, "etc/gitconfig")
        gitconfig.parentFile?.mkdirs()
        gitconfig.writeText("[safe]\ndirectory=*\n")
        markOld(gitconfig)

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        assertNotTouched(gitconfig)
    }

    @Test
    fun `CRLF line endings are recognised`() {
        val rootfs = rootfs()
        val gitconfig = File(rootfs, "etc/gitconfig")
        gitconfig.parentFile?.mkdirs()
        gitconfig.writeText("[user]\r\n\tname = x\r\n[safe]\r\n\tdirectory = *\r\n")
        markOld(gitconfig)

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        assertNotTouched(gitconfig)
    }

    @Test
    fun `a subsection named safe is not mistaken for the safe section`() {
        
        val rootfs = rootfs()
        val gitconfig = File(rootfs, "etc/gitconfig")
        gitconfig.parentFile?.mkdirs()
        gitconfig.writeText("[safe \"sub\"]\n\tdirectory = *\n")

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val text = gitconfig.readText()
        assertEquals("a [safe] section must be appended", 1, Regex("(?m)^\\[safe\\]$").findAll(text).count())
        assertTrue(text.contains("[safe \"sub\"]"))
    }

    @Test
    fun `a commented out key does not count`() {
        val rootfs = rootfs()
        val gitconfig = File(rootfs, "etc/gitconfig")
        gitconfig.parentFile?.mkdirs()
        gitconfig.writeText("# directory = *\n[user]\n\tname = someone\n")

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val text = gitconfig.readText()
        assertTrue(text.contains("[safe]"))
        assertTrue("the user's content must survive", text.startsWith("# directory = *\n[user]\n\tname = someone\n"))
    }

    @Test
    fun `a file without a trailing newline gets exactly one separating newline`() {
        val rootfs = rootfs()
        val gitconfig = File(rootfs, "etc/gitconfig")
        gitconfig.parentFile?.mkdirs()
        gitconfig.writeText("[user]\n\tname = someone") 

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val text = gitconfig.readText()
        assertTrue("the user's content must come first", text.startsWith("[user]\n\tname = someone\n"))
        
        assertTrue("one newline must separate the content from the appended block", text.contains("someone\n# ---"))
        assertFalse("no blank line should be invented", text.contains("someone\n\n"))
    }

    

    @Test
    fun `CRLF duplicates of already present mirrors are not appended twice`() {
        val rootfs = rootfs()
        val repositories = File(rootfs, "etc/apk/repositories")
        val first = repositoryLines("v3.24").first()
        repositories.writeText("$first\r\n$first\r\n")

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val lines = repositories.readLines().filter(String::isNotBlank)
        
        assertEquals("each mirror line is present", 16, lines.toSet().size)
        assertEquals("the pre-existing duplicate stays as it was", 17, lines.size)
        assertEquals(2, lines.count { it == first })
    }

    @Test
    fun `a file repository line and the user's own mirror line survive`() {
        val rootfs = rootfs()
        val repositories = File(rootfs, "etc/apk/repositories")
        val custom = "http://192.168.1.10/alpine/v3.24/main"
        repositories.writeText("file:///var/cache/prefetch\n$custom\n")

        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val lines = repositories.readLines().filter(String::isNotBlank)
        assertTrue(lines.contains("file:///var/cache/prefetch"))
        assertTrue(lines.contains(custom))
        assertEquals(18, lines.size) 
    }

    @Test
    fun `an alpine release with a leading v still yields a vMAJOR dot MINOR branch`() {
        val rootfs = rootfs(alpineRelease = "v3.24.1\n")
        RuntimeGuestConfig.ensureUpToDate(rootfs)

        val lines = File(rootfs, "etc/apk/repositories").readLines()
        assertEquals(16, lines.size)
        assertTrue(lines.all { it.contains("/v3.24/") })
    }

    @Test
    fun `a two component alpine release yields the same branch`() {
        val rootfs = rootfs(alpineRelease = "3.24\n")
        RuntimeGuestConfig.ensureUpToDate(rootfs)
        assertTrue(File(rootfs, "etc/apk/repositories").readLines().all { it.contains("/v3.24/") })
    }

    @Test
    fun `a single component alpine release is not guessed into a branch`() {
        
        val rootfs = rootfs(alpineRelease = "3\n")
        RuntimeGuestConfig.ensureUpToDate(rootfs)
        assertFalse("must not guess a branch", File(rootfs, "etc/apk/repositories").exists())
    }

    @Test
    fun `surrounding blank lines in alpine release are ignored`() {
        val rootfs = rootfs(alpineRelease = "\n\n  3.24.1  \n")
        RuntimeGuestConfig.ensureUpToDate(rootfs)
        assertTrue(File(rootfs, "etc/apk/repositories").readLines().all { it.contains("/v3.24/") })
    }

    

    @Test
    fun `a second run writes none of the four files`() {
        val rootfs = rootfs()
        RuntimeGuestConfig.ensureUpToDate(rootfs, dnsServers = listOf("192.168.1.1"))

        val files =
            listOf(
                "etc/gitconfig",
                "etc/apk/repositories",
                "etc/profile.d/android-code.sh",
                "etc/resolv.conf",
            ).map { File(rootfs, it) }
        files.forEach { file ->
            assertTrue("expected file missing: ${file.absolutePath}", file.isFile)
            markOld(file)
        }
        val before = files.map { it.readText() }

        RuntimeGuestConfig.ensureUpToDate(rootfs, dnsServers = listOf("192.168.1.1"))

        files.forEachIndexed { index, file ->
            assertEquals("content changed on the second run: ${file.absolutePath}", before[index], file.readText())
            assertNotTouched(file)
        }
    }

    private companion object {
        
        const val OLD_TIMESTAMP = 1_000_000_000_000L
    }
}
