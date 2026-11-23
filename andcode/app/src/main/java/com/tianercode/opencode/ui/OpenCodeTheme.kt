package com.tianercode.opencode.ui

import android.graphics.Color
































internal object OpenCodeTheme {
    
    val backgroundBase = Color.parseColor("#101010")
    val backgroundWeak = Color.parseColor("#1e1e1e")
    val backgroundStrong = Color.parseColor("#121212")
    val backgroundStronger = Color.parseColor("#151515")

    
    val surfaceBase = rgba("#ffffff08")
    val surfaceBaseHover = rgba("#ffffff0a")
    val surfaceBaseActive = rgba("#ffffff0f")
    val surfaceRaisedBase = rgba("#ffffff0f")
    val surfaceRaisedBaseHover = rgba("#ffffff14")
    val surfaceFloatBase = Color.parseColor("#161616")

    
    val textBase = rgba("#ffffff9e")
    val textWeak = rgba("#ffffff6c")
    val textWeaker = rgba("#ffffff48")
    val textStrong = rgba("#ffffffef")
    val textInteractive = Color.parseColor("#9dbefe")
    val textOnSuccess = Color.parseColor("#12c905")
    val textOnCritical = Color.parseColor("#fc533a")
    val textOnWarning = rgba("#ffffff9a")

    
    val borderBase = rgba("#ffffff32")
    val borderWeak = Color.parseColor("#282828")
    val borderWeaker = Color.parseColor("#202020")
    val borderHover = rgba("#ffffff48")

    
    val inputBase = Color.parseColor("#1c1c1c")
    val inputActive = Color.parseColor("#091123")
    val buttonPrimary = Color.parseColor("#ededed")
    val buttonSecondary = Color.parseColor("#1c1c1c")
    val buttonSecondaryHover = rgba("#ffffff0a")
    val buttonGhostHover = rgba("#ffffff08")

    
    val surfaceCriticalStrong = Color.parseColor("#fc533a")
    val surfaceSuccessStrong = Color.parseColor("#12c905")
    val surfaceWarningStrong = Color.parseColor("#fcd53a")
    val surfaceBrandBase = Color.parseColor("#fab283")

    
    
    
    
    
    val v2BackgroundBase = Color.parseColor("#161616")
    val v2BackgroundDeep = Color.parseColor("#080808")
    val v2BackgroundLayer01 = Color.parseColor("#242424")
    val v2BackgroundLayer02 = Color.parseColor("#2e2e2e")
    val v2BackgroundButtonNeutral = rgba("#ffffff0f")
    val v2BorderBase = rgba("#ffffff1a")
    val v2TextBase = Color.parseColor("#fafafa")
    val v2TextMuted = Color.parseColor("#aeaeae")
    val v2TextFaint = Color.parseColor("#808080")
    val v2TextCodeAccent = Color.parseColor("#a2bcff")
    val v2IconMuted = Color.parseColor("#808080")
    val v2BackgroundContrast = Color.parseColor("#5c5c5c")
    val v2TextContrast = Color.parseColor("#ffffff")
    val v2StateFgSuccess = Color.parseColor("#6bd586")
    val v2StateFgDanger = Color.parseColor("#f17471")
    val v2StateFgInfo = Color.parseColor("#7698fd")
    val v2OverlayHover = rgba("#ffffff0f")
    val v2OverlayPressed = rgba("#ffffff1a")

    
    
    
    

    
    const val RADIUS_XS = 2

    
    const val RADIUS_SM = 4

    
    const val RADIUS_MD = 6

    
    const val RADIUS_LG = 8

    
    const val RADIUS_XL = 10

    
    const val TEXT_SMALL = 13f

    
    const val TEXT_BASE = 14f

    
    const val TEXT_LARGE = 16f

    
    const val TEXT_X_LARGE = 20f

    
    const val WEIGHT_BOLD = 670

    
    const val WEIGHT_REGULAR = 400

    



    const val FONT_MONO = "IBM Plex Mono"

    




    fun rgba(cssHex: String): Int {
        val hex = cssHex.removePrefix("#")
        return when (hex.length) {
            6 -> Color.parseColor("#$hex")
            8 -> {
                val rgb = hex.substring(0, 6)
                val alpha = hex.substring(6, 8)
                Color.parseColor("#$alpha$rgb")
            }
            else -> Color.MAGENTA 
        }
    }
}
