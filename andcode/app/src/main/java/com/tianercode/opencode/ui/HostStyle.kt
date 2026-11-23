package com.tianercode.opencode.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tianercode.opencode.R
import java.util.concurrent.ConcurrentHashMap



































internal object HostStyle {
    
    
    const val TEXT_TITLE = 15f
    
    const val TEXT_BODY = 13f

    
    const val WEIGHT_TITLE = 530
    const val WEIGHT_BODY = 440
    const val WEIGHT_BUTTON = 530

    
    private const val LINE_HEIGHT_PX = 20f
    private const val TITLE_LETTER_SPACING_PX = -0.13f
    private const val BODY_LETTER_SPACING_PX = -0.04f

    
    
    const val RADIUS_CONTROL = OpenCodeTheme.RADIUS_MD

    




    const val BUTTON_HEIGHT_DP = 48
    const val BUTTON_MIN_WIDTH_DP = 48

    
    const val BUTTON_PADDING_H_DP = 12

    const val BORDER_WIDTH_DP = 1

    

    
    private const val INTER_ASSET = "desktop-web/out/renderer/assets/Inter.ttf"

    
    private const val MONO_ASSET = "fonts/IBMPlexMono-Text.ttf"

    private val typefaceCache = ConcurrentHashMap<Int, Typeface>()

    





    fun sans(context: Context, weight: Int): Typeface =
        typefaceCache.getOrPut(weight) {
            runCatching {
                Typeface.Builder(context.assets, INTER_ASSET)
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }.getOrNull()
        } ?: run {
            
            val fallbackWeight = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
            Typeface.create(Typeface.SANS_SERIF, fallbackWeight)
        }

    
















    fun mono(context: Context): Typeface =
        runCatching {
            Typeface.Builder(context.assets, MONO_ASSET).build()
        }.getOrElse { Typeface.MONOSPACE }


    
    fun color(context: Context, resId: Int): Int = ContextCompat.getColor(context, resId)

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun dpFloat(context: Context, value: Int): Float = value * context.resources.displayMetrics.density

    

    
    fun applyTitle(textView: TextView) {
        textView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_TITLE)
            typeface = sans(context, WEIGHT_TITLE)
            setTextColor(color(context, R.color.oc_text_primary))
            setLineSpacing(0f, LINE_HEIGHT_PX / TEXT_TITLE)
            letterSpacing = TITLE_LETTER_SPACING_PX / TEXT_TITLE
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
    }

    
    fun applyBody(textView: TextView, colorRes: Int = R.color.oc_text_secondary) {
        textView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_BODY)
            typeface = sans(context, WEIGHT_BODY)
            setTextColor(color(context, colorRes))
            setLineSpacing(0f, LINE_HEIGHT_PX / TEXT_BODY)
            letterSpacing = BODY_LETTER_SPACING_PX / TEXT_BODY
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
    }

    





    enum class ButtonVariant { PRIMARY, SECONDARY }

    






    fun applyButton(button: Button, variant: ButtonVariant) {
        val context = button.context
        val fillRes = if (variant == ButtonVariant.PRIMARY) R.color.oc_button_primary_bg else R.color.oc_button_secondary_bg
        val textRes = if (variant == ButtonVariant.PRIMARY) R.color.oc_button_primary_text else R.color.oc_button_secondary_text

        button.apply {
            isAllCaps = false
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_BODY)
            typeface = sans(context, WEIGHT_BUTTON)
            letterSpacing = BODY_LETTER_SPACING_PX / TEXT_BODY
            setTextColor(color(context, textRes))
            minHeight = dp(context, BUTTON_HEIGHT_DP)
            minimumHeight = dp(context, BUTTON_HEIGHT_DP)
            minWidth = dp(context, BUTTON_MIN_WIDTH_DP)
            minimumWidth = dp(context, BUTTON_MIN_WIDTH_DP)
            setPadding(
                dp(context, BUTTON_PADDING_H_DP),
                0,
                dp(context, BUTTON_PADDING_H_DP),
                0,
            )
            stateListAnimator = null
            elevation = 0f
            background = controlBackground(context, fillRes, variant)
        }
    }

    private fun controlBackground(context: Context, fillRes: Int, variant: ButtonVariant): RippleDrawable {
        val shape =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpFloat(context, RADIUS_CONTROL)
                setColor(color(context, fillRes))
                if (variant == ButtonVariant.SECONDARY) {
                    
                    
                    setStroke(dp(context, BORDER_WIDTH_DP), color(context, R.color.oc_border_base))
                }
            }
        val rippleRes = if (variant == ButtonVariant.PRIMARY) R.color.oc_ripple_primary else R.color.oc_ripple
        return RippleDrawable(ColorStateList.valueOf(color(context, rippleRes)), shape, null)
    }
}
