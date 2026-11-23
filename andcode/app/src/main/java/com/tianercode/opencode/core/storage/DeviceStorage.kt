package com.tianercode.opencode.core.storage

import java.io.File















object DeviceStorage {
    
    const val GUEST_SHARED_ROOT = "/sdcard"

    




    const val GUEST_VOLUMES_ROOT = "/storage"

    
    data class Mounts(
        val sharedStorage: File? = null,
        val volumes: File? = null,
    ) {
        val isEmpty: Boolean
            get() = sharedStorage == null && volumes == null

        companion object {
            val None = Mounts()
        }
    }

    








    @Volatile
    private var provider: () -> Mounts = { Mounts.None }

    fun install(provider: () -> Mounts) {
        this.provider = provider
    }

    fun mounts(): Mounts = provider()

    
    fun bindArguments(): List<String> = bindArguments(mounts())

    









    fun bindArguments(mounts: Mounts): List<String> =
        buildList {
            mounts.volumes?.takeIf { it.isDirectory }?.let {
                add("-b")
                add("${it.absolutePath}:$GUEST_VOLUMES_ROOT")
            }
            mounts.sharedStorage?.takeIf { it.isDirectory }?.let {
                add("-b")
                add("${it.absolutePath}:$GUEST_SHARED_ROOT")
            }
        }


    



    fun hostDirectory(
        mounts: Mounts,
        path: String,
    ): File? {
        val root = guestRootOf(path) ?: return null
        val host =
            when (root) {
                GUEST_SHARED_ROOT -> mounts.sharedStorage
                else -> mounts.volumes
            } ?: return null
        val relative = path.removePrefix(root).trim('/')
        val base = host.absoluteFile.normalize()
        if (relative.isEmpty()) return base
        val resolved = File(base, relative).absoluteFile.normalize()
        return resolved.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun guestRootOf(path: String): String? =
        listOf(GUEST_SHARED_ROOT, GUEST_VOLUMES_ROOT)
            .firstOrNull { path == it || path.startsWith("$it/") }
}
