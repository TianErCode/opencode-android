package com.tianercode.opencode.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tianercode.opencode.R
import kotlin.math.min
import kotlin.math.roundToInt





















class OpenCodeLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val innerPath: Path = Path().apply { parseAbsolutePath(LOGO_INNER, this) }
    private val outerPath: Path = Path().apply { parseAbsolutePath(LOGO_OUTER, this) }

    private val innerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.oc_icon_base)
        }
    private val outerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.oc_icon_strong)
        }

    init {
        
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
    }

    
    fun refreshColors() {
        innerPaint.color = ContextCompat.getColor(context, R.color.oc_icon_base)
        outerPaint.color = ContextCompat.getColor(context, R.color.oc_icon_strong)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(HostStyle.dp(context, DEFAULT_WIDTH_DP), widthMeasureSpec)
        val proportionalHeight = (width * VIEWBOX_HEIGHT / VIEWBOX_WIDTH).roundToInt()
        setMeasuredDimension(width, resolveSize(proportionalHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / VIEWBOX_WIDTH, height / VIEWBOX_HEIGHT)
        if (scale <= 0f) return
        canvas.save()
        canvas.translate((width - VIEWBOX_WIDTH * scale) / 2f, (height - VIEWBOX_HEIGHT * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(innerPath, innerPaint)
        canvas.drawPath(outerPath, outerPaint)
        canvas.restore()
    }

    private companion object {
        const val DEFAULT_WIDTH_DP = 64
        const val VIEWBOX_WIDTH = 80f
        const val VIEWBOX_HEIGHT = 100f

        
        const val LOGO_INNER = "M60 80H20V40H60V80Z"

        
        const val LOGO_OUTER = "M60 20H20V80H60V20ZM80 100H0V0H80V100Z"
    }
}







private fun parseAbsolutePath(data: String, path: Path) {
    val tokens = Regex("[MHVZLmhvzl]|-?\\d+(?:\\.\\d+)?").findAll(data).map { it.value }.toList()
    var i = 0
    var currentX = 0f
    var currentY = 0f
    var command = 'M'

    fun readNumber(): Float = tokens[i++].toFloat()

    while (i < tokens.size) {
        val token = tokens[i]
        if (token.length == 1 && token[0].isLetter()) {
            command = token[0]
            i++
            if (command == 'Z' || command == 'z') {
                path.close()
                continue
            }
        }
        when (command) {
            'M' -> {
                currentX = readNumber()
                currentY = readNumber()
                path.moveTo(currentX, currentY)
                command = 'L'
            }
            'L' -> {
                currentX = readNumber()
                currentY = readNumber()
                path.lineTo(currentX, currentY)
            }
            'H' -> {
                currentX = readNumber()
                path.lineTo(currentX, currentY)
            }
            'V' -> {
                currentY = readNumber()
                path.lineTo(currentX, currentY)
            }
            'Z', 'z' -> path.close()
            else -> return
        }
    }
}
