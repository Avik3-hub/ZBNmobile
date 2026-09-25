package com.example.zbnreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Quiet blueprint-style decoration. It never handles input and has no app logic. */
class AviationBackdropView(context: Context) : View(context) {
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#101A24")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = dp(42f)
        var x = 0f
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), blue)
            x += step
        }
        var y = dp(74f)
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, blue)
            y += step
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
