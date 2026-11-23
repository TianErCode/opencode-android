package com.tianercode.opencode.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean















class RuntimeWorkTracker {
    private val lock = Any()
    private val counts = mutableMapOf<String, Int>()

    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()


    interface Lease {
        



        fun release()
    }

    fun acquire(tag: String): Lease {
        synchronized(lock) {
            counts[tag] = (counts[tag] ?: 0) + 1
            publishLocked()
        }
        return TrackedLease(tag)
    }

    
    suspend fun <T> withLease(
        tag: String,
        block: suspend () -> T,
    ): T {
        val lease = acquire(tag)
        try {
            return block()
        } finally {
            lease.release()
        }
    }

    private fun release(tag: String) {
        synchronized(lock) {
            val remaining = (counts[tag] ?: return) - 1
            if (remaining <= 0) counts.remove(tag) else counts[tag] = remaining
            publishLocked()
        }
    }

    
    private fun publishLocked() {
        mutableActive.value = counts.isNotEmpty()
    }

    




    private inner class TrackedLease(private val tag: String) : Lease {
        private val released = AtomicBoolean(false)

        override fun release() {
            if (released.compareAndSet(false, true)) release(tag)
        }
    }
}
