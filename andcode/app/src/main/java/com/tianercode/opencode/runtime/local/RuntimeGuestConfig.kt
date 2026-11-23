package com.tianercode.opencode.runtime.local

import java.io.File






































internal object RuntimeGuestConfig {
    
    private const val LOG_TAG = "AndCode.Runtime"

    






    fun ensureUpToDate(
        rootfs: File,
        dnsServers: List<String> = emptyList(),
    ) {
        if (!rootfs.isDirectory) return
        runCatching { ensureGitConfig(File(rootfs, "etc/gitconfig")) }
            .onFailure { error ->
                android.util.Log.w(LOG_TAG, "guest config: could not verify /etc/gitconfig", error)
            }
        runCatching { ensureApkRepositories(File(rootfs, "etc/apk/repositories"), rootfs) }
            .onFailure { error ->
                android.util.Log.w(LOG_TAG, "guest config: could not verify /etc/apk/repositories", error)
            }
        runCatching { ensureProfile(File(rootfs, "etc/profile.d/android-code.sh")) }
            .onFailure { error ->
                android.util.Log.w(LOG_TAG, "guest config: could not verify /etc/profile.d/android-code.sh", error)
            }
        runCatching { ensureResolvConf(File(rootfs, "etc/resolv.conf"), dnsServers) }
            .onFailure { error ->
                android.util.Log.w(LOG_TAG, "guest config: could not verify /etc/resolv.conf", error)
            }
    }

    
    
    

    



    private fun ensureProfile(file: File) {
        val desired = PROFILE_CONTENT
        val existing = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
        if (existing == desired) return
        file.parentFile?.mkdirs()
        file.writeText(desired)
        android.util.Log.i(LOG_TAG, "guest config: rewrote ${file.absolutePath}")
    }

    
    
    

    






    private fun ensureResolvConf(
        file: File,
        dnsServers: List<String>,
    ) {
        
        if (dnsServers.isEmpty()) return
        val desired = resolvConfContent(dnsServers)
        val existing = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
        if (existing == desired) return
        file.parentFile?.mkdirs()
        file.writeText(desired)
        android.util.Log.i(LOG_TAG, "guest config: rewrote ${file.absolutePath} for the current network")
    }

    
    
    

    







    private fun ensureGitConfig(file: File) {
        val existing =
            runCatching { if (file.isFile) file.readText() else "" }
                .getOrDefault("")
        if (gitConfigAllowsEveryDirectory(existing)) return
        val prefix =
            when {
                existing.isEmpty() -> ""
                existing.endsWith("\n") -> existing
                else -> existing + "\n"
            }
        file.parentFile?.mkdirs()
        file.writeText(prefix + GIT_SAFE_DIRECTORY_BLOCK)
        android.util.Log.i(
            LOG_TAG,
            "guest config: added [safe] directory = * to ${file.absolutePath}",
        )
    }

    










    private fun gitConfigAllowsEveryDirectory(text: String): Boolean {
        var inSafeSection = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
            if (line.startsWith('[')) {
                inSafeSection = SAFE_SECTION_HEADER.matches(line)
                continue
            }
            if (!inSafeSection) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            if (key.equals("directory", ignoreCase = true) && value == "*") return true
        }
        return false
    }

    
    
    

    









    private fun ensureApkRepositories(
        file: File,
        rootfs: File,
    ) {
        
        
        
        val branch = alpineBranch(rootfs, file) ?: return
        val desired = apkRepositoryLines(branch)
        val existingText =
            runCatching { if (file.isFile) file.readText() else "" }
                .getOrDefault("")
        val existingLines =
            existingText.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        val missing = desired.filterNot(existingLines::contains)
        if (missing.isEmpty()) return
        val prefix =
            when {
                existingText.isEmpty() -> ""
                existingText.endsWith("\n") -> existingText
                else -> existingText + "\n"
            }
        file.parentFile?.mkdirs()
        file.writeText(prefix + missing.joinToString("\n", postfix = "\n"))
        android.util.Log.i(
            LOG_TAG,
            "guest config: added ${missing.size} missing apk repository line(s) to ${file.absolutePath}",
        )
    }

    






    private fun alpineBranch(
        rootfs: File,
        repositoriesFile: File,
    ): String? {
        val releaseVersion =
            runCatching { File(rootfs, "etc/alpine-release").takeIf(File::isFile)?.readText() }
                .getOrNull()
                ?.lineSequence()
                ?.map(String::trim)
                ?.firstOrNull(String::isNotEmpty)
                ?.trimStart('v')
        if (releaseVersion != null) {
            val parts = releaseVersion.split('.').filter(String::isNotBlank)
            if (parts.size >= 2) {
                val candidate = "v" + parts[0] + "." + parts[1]
                if (BRANCH_PATTERN.matches(candidate)) return candidate
            }
        }
        val existing =
            runCatching { repositoriesFile.takeIf(File::isFile)?.readText() }
                .getOrNull()
                ?: return null
        return BRANCH_IN_REPOSITORY.find(existing)?.groupValues?.get(1)
            ?.takeIf(BRANCH_PATTERN::matches)
    }

    



    private fun apkRepositoryLines(branch: String): List<String> =
        APK_MIRROR_BASES.flatMap { base ->
            APK_REPOSITORIES.map { repository -> "$base/$branch/$repository" }
        }

    
    
    

    





    private val GIT_SAFE_DIRECTORY_BLOCK =
        "# --- 由 Android 宿主在运行时启动前补写（LocalRuntimeManager.startInstalled）---\n" +
            "# 沙箱是单用户环境：/sdcard 的属主报的是 App 的 uid，而 guest 里是 root。\n" +
            "# git 会因此拒绝用户自己的仓库（dubious ownership）；这里没有要保护的对象。\n" +
            "# 只在该键缺失时追加，用户在此文件里的其他配置不受影响。\n" +
            "[safe]\n" +
            "\tdirectory = *\n"

    private val SAFE_SECTION_HEADER = Regex("^\\[\\s*safe\\s*\\]$", RegexOption.IGNORE_CASE)

    
    private val BRANCH_IN_REPOSITORY = Regex("/(v\\d+\\.\\d+)/")

    
    private val BRANCH_PATTERN = Regex("^v\\d+\\.\\d+$")

    
    private val APK_REPOSITORIES = ApkRepositoryDefaults.REPOSITORIES

    






    private val APK_MIRROR_BASES = ApkRepositoryDefaults.MIRROR_BASES

    





    internal const val PROFILE_CONTENT =
        "export HOME=/root\n" +
            "export TMPDIR=/tmp\n" +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin\n" +
            "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk\n" +
            "export OPENCODE_CONFIG_DIR=/root/.config/opencode\n"

    








    internal fun resolvConfContent(dnsServers: List<String>): String =
        buildString {
            for (server in dnsServers) {
                append("nameserver ").append(server).append('\n')
            }
            append("nameserver 223.5.5.5\n") 
            append("nameserver 119.29.29.29\n") 
            append("nameserver 1.1.1.1\n")
            append("nameserver 8.8.8.8\n")
        }
}
