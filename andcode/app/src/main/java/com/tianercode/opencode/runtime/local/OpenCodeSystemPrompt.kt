package com.tianercode.opencode.runtime.local

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption









internal const val OPENCODE_SYSTEM_PROMPT_PATH = "root/.config/opencode/opencode-system-prompt.md"


































internal fun applyOpenCodeSystemPrompt(
    rootfs: File,
    prompt: String?,
) {
    val rootfsCanonical =
        try {
            rootfs.canonicalFile
        } catch (e: IOException) {
            rootfs.absoluteFile
        }
    val target = manageablePathOrNull(rootfsCanonical, File(rootfs, OPENCODE_SYSTEM_PROMPT_PATH)) ?: return
    try {
        if (prompt.isNullOrBlank()) {
            
            
            target.delete()
            return
        }
        target.parentFile?.mkdirs()
        
        
        
        
        
        val staging = File.createTempFile("${target.name}.", ".staged", target.parentFile)
        try {
            Files
                .newOutputStream(
                    staging.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { out -> out.write(prompt.toByteArray()) }
            
            
            
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            
            
            staging.delete()
        }
    } catch (e: IOException) {
        
    } catch (e: UnsupportedOperationException) {
        
        
        
    }
}
