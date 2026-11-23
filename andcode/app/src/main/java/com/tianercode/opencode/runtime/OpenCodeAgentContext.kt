package com.tianercode.opencode.runtime.local

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

internal const val OPENCODE_AGENT_CONTEXT_ASSET = "opencode-agent-context.md"

private const val RUNTIME_CONTEXT_PATH = "root/.config/opencode/agent-context.md"






private const val WRITTEN_HASHES_PATH = "root/.config/opencode/agent-context-written.tsv"







private const val MAX_WRITTEN_HASHES_BYTES = 64L * 1024

private val AGENT_CONTEXT_PATHS =
    listOf(
        "root/.config/opencode/opencode-context.md",
        "root/.claude/CLAUDE.md",
        "root/.gemini/GEMINI.md",
    )

internal fun ensureOpenCodeAgentContext(
    rootfs: File,
    context: Context,
) {
    val agentContext =
        context.assets.open(OPENCODE_AGENT_CONTEXT_ASSET).use { input ->
            input.readBytes()
        }
    installOpenCodeAgentContext(rootfs, agentContext)
}











































internal fun installOpenCodeAgentContext(
    rootfs: File,
    agentContext: ByteArray,
) {
    
    
    
    
    
    
    
    val rootfsCanonical =
        try {
            rootfs.canonicalFile
        } catch (e: IOException) {
            rootfs.absoluteFile
        }
    val agentContextHash = sha256Hex(agentContext)

    
    
    
    
    
    manageablePathOrNull(rootfsCanonical, File(rootfs, RUNTIME_CONTEXT_PATH))?.let { source ->
        try {
            source.parentFile?.mkdirs()
            source.writeBytes(agentContext)
        } catch (e: IOException) {
            
        }
    }

    val writtenHashesFile = manageablePathOrNull(rootfsCanonical, File(rootfs, WRITTEN_HASHES_PATH))
    
    
    val writtenHashes = writtenHashesFile?.let(::readWrittenHashes).orEmpty().toMutableMap()
    var hashesChanged = false

    AGENT_CONTEXT_PATHS.forEach { relativePath ->
        val target = manageablePathOrNull(rootfsCanonical, File(rootfs, relativePath)) ?: return@forEach
        
        
        
        
        
        
        
        try {
            val currentHash = if (target.isFile) RuntimeArchive.sha256(target) else null
            
            
            
            
            
            val safeToManage =
                currentHash == null || currentHash == agentContextHash || currentHash == writtenHashes[relativePath]
            if (!safeToManage) {
                
                
                return@forEach
            }
            if (currentHash != agentContextHash) {
                target.parentFile?.mkdirs()
                target.writeBytes(agentContext)
            }
            if (writtenHashes[relativePath] != agentContextHash) {
                writtenHashes[relativePath] = agentContextHash
                hashesChanged = true
            }
        } catch (e: IOException) {
            
        }
    }

    
    
    
    if (hashesChanged && writtenHashesFile != null) {
        writeWrittenHashes(writtenHashesFile, writtenHashes)
    }
}


private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}




















internal fun manageablePathOrNull(
    rootfsCanonical: File,
    target: File,
): File? {
    val canonical =
        try {
            target.canonicalFile
        } catch (e: IOException) {
            return null
        }
    if (!canonical.toPath().startsWith(rootfsCanonical.toPath())) return null
    if (Files.isSymbolicLink(target.toPath())) return null
    if (target.exists() && !target.isFile) return null
    return target
}















private fun readWrittenHashes(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    if (file.length() > MAX_WRITTEN_HASHES_BYTES) return emptyMap()
    return try {
        file.useLines { lines ->
            lines
                .mapNotNull { line ->
                    val separator = line.indexOf('\t')
                    if (separator < 0) return@mapNotNull null
                    val path = line.substring(0, separator)
                    if (path !in AGENT_CONTEXT_PATHS) return@mapNotNull null
                    path to line.substring(separator + 1)
                }
                .toMap()
        }
    } catch (e: IOException) {
        emptyMap()
    }
}





private fun writeWrittenHashes(
    file: File,
    hashes: Map<String, String>,
) {
    try {
        file.parentFile?.mkdirs()
        file.writeText(hashes.entries.joinToString("\n") { (path, hash) -> "$path\t$hash" })
    } catch (e: IOException) {
        
    }
}
