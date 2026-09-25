package com.example.zbnreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class A4GuideView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8C7FA")
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        // Leave breathing room around the whole A4 outline inside the 4:3 preview.
        val horizontalPadding = 48f * resources.displayMetrics.density
        val verticalPadding = 96f * resources.displayMetrics.density
        val maxWidth = (width - horizontalPadding * 2).coerceAtLeast(1f)
        val maxHeight = (height - verticalPadding * 2).coerceAtLeast(1f)
        val paperHeight = min(maxHeight, maxWidth * sqrt(2f))
        val paperWidth = paperHeight / sqrt(2f)
        val left = (width - paperWidth) / 2f
        val top = (height - paperHeight) / 2f
        canvas.drawRoundRect(RectF(left, top, left + paperWidth, top + paperHeight), 8f, 8f, paint)
    }
}
