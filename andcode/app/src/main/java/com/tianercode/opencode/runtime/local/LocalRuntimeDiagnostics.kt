package com.tianercode.opencode.runtime.local

import java.io.File

data class LocalRuntimeProcessMetrics(
    val pid: Long?,
    val rssBytes: Long?,
    val uptimeMillis: Long,
    val restartCount: Int = 0,
    val lastExitCode: Int? = null,
    val lastExitAtMillis: Long? = null,
)

data class LocalRuntimeCommandResult(
    val exitCode: Int,
    val output: String,
)

data class LocalRuntimeToolDefinition(
    val id: String,
    val label: String,
    val command: String,
)

internal fun readDirectChildPids(
    parentPid: Long,
    procRoot: File = File("/proc"),
): List<Long> {
    val standardChildren = File(procRoot, "$parentPid/task/$parentPid/children")
    if (standardChildren.isFile) {
        return runCatching {
            standardChildren.readText()
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull(String::toLongOrNull)
                .sorted()
        }.getOrDefault(emptyList())
    }

    return procRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            val pid = directory.name.toLongOrNull() ?: return@mapNotNull null
            val statusText =
                runCatching {
                    File(directory, "status").readText()
                }.getOrNull() ?: return@mapNotNull null
            pid.takeIf { parseParentProcessId(statusText) == parentPid }
        }
        .sorted()
        .toList()
}

internal fun parseParentProcessId(statusText: String): Long? =
    statusText.lineSequence()
        .firstOrNull { it.startsWith("PPid:") }
        ?.substringAfter("PPid:")
        ?.trim()
        ?.substringBefore(' ')
        ?.toLongOrNull()

internal fun totalResidentSetBytes(
    rootPid: Long,
    statusReader: (Long) -> String?,
    childrenReader: (Long) -> List<Long>,
): Long? {
    val pending = ArrayDeque<Long>().apply { add(rootPid) }
    val visited = mutableSetOf<Long>()
    var total = 0L
    var found = false
    while (pending.isNotEmpty()) {
        val pid = pending.removeFirst()
        if (!visited.add(pid)) continue
        statusReader(pid)?.let(::parseResidentSetBytes)?.let { rssBytes ->
            total += rssBytes
            found = true
        }
        childrenReader(pid).forEach(pending::addLast)
    }
    return total.takeIf { found }
}

internal fun parseResidentSetBytes(statusText: String): Long? {
    val valueKb =
        statusText.lineSequence()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter("VmRSS:")
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()
    return valueKb?.times(1024L)
}
