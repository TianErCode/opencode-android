package com.tianercode.opencode.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow








interface AppForeground {
    val foreground: StateFlow<Boolean>
}








class ProcessLifecycleAppForeground private constructor() : AppForeground, DefaultLifecycleObserver {
    private val mutableForeground = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = mutableForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        mutableForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        mutableForeground.value = false
    }

    companion object {
        
        fun install(): ProcessLifecycleAppForeground =
            ProcessLifecycleAppForeground().also { instance ->
                ProcessLifecycleOwner.get().lifecycle.addObserver(instance)
            }
    }
}
