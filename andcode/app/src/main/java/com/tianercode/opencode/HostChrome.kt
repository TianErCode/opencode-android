package com.tianercode.opencode

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat















class HostChrome(private val activity: Activity) {
    




    private var lastBackgroundColor: Int = 0

    
    private var lastUiMode: Int =
        activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    
    private var overlayBackgroundColor: Int? = null

    
    val themeBackground: Int
        get() = ContextCompat.getColor(activity, R.color.oc_background)

    






    val pageBackground: Int
        get() = if (lastBackgroundColor == 0) themeBackground else pageCanvasColor(lastBackgroundColor)

    









    private fun pageCanvasColor(argb: Int): Int =
        ContextCompat.getColor(
            activity,
            if (isLightColor(argb)) R.color.oc_page_canvas_light else R.color.oc_page_canvas_dark,
        )

    





    fun applyPageBackground(argb: Int) {
        lastBackgroundColor = argb
        if (overlayBackgroundColor != null) return
        paint(pageCanvasColor(argb))
    }

    





    fun setOverlayBackground(argb: Int?) {
        overlayBackgroundColor = argb
    }

    





    fun applyPalette() {
        if (lastBackgroundColor == 0) {
            paint(overlayBackgroundColor ?: themeBackground)
        } else {
            paint(overlayBackgroundColor ?: pageCanvasColor(lastBackgroundColor))
        }
    }

    





    fun onConfigurationChanged(newConfig: Configuration) {
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMode != lastUiMode) {
            
            
            
            
            
            lastUiMode = nightMode
            lastBackgroundColor = 0
        }
        applyPalette()
        applyMaxRefreshRate()
    }

    
    private fun paint(argb: Int) {
        activity.window.decorView.setBackgroundColor(argb)
        
        activity.findViewById<View>(R.id.web_host)?.setBackgroundColor(argb)
        updateSystemBarAppearance(argb)
    }

    







    private fun updateSystemBarAppearance(argb: Int) {
        val lightBackground = isLightColor(argb)
        runCatching {
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = lightBackground
            controller.isAppearanceLightNavigationBars = lightBackground
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
    }

    
    fun isLightColor(argb: Int): Boolean {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0 > 0.5
    }

    







    fun applyMaxRefreshRate() {
        runCatching {
            val display =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
            val mode = display?.supportedModes?.maxByOrNull { it.refreshRate } ?: return
            activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = mode.refreshRate }
            
            
            
            if (Build.VERSION.SDK_INT >= 35) {
                activity.window.setFrameRateBoostOnTouchEnabled(true)
            }
        }
    }
}
