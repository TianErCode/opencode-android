package com.tianercode.opencode



























internal object BackPolicy {

    enum class Action {
        
        CloseRescue,

        
        GoBackInPage,

        
        ArmExit,

        

        Exit,
    }

    fun decide(
        rescueOpen: Boolean,
        canGoBackInPage: Boolean,
        atHome: Boolean,
        exitArmed: Boolean,
    ): Action =
        when {
            rescueOpen -> Action.CloseRescue
            
            
            exitArmed -> Action.Exit
            !atHome && canGoBackInPage -> Action.GoBackInPage
            else -> Action.ArmExit
        }

    








    fun isHomePath(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val path =
            runCatching { java.net.URI(url).path }.getOrNull()
                ?: return true
        return path.isEmpty() || path == "/" || path == "/index.html" || path == "/__boot"
    }
}
