package com.example.zbnreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Quiet blueprint-style decoration. It never handles input and has no app logic. */
class AviationBackdropView(context: Context) : View(context) {
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#182A3D")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val amber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C3517")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val path = Path()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        drawHelicopterBlueprint(canvas)
        drawHelipad(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = dp(28f)
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

    private fun drawHelicopterBlueprint(canvas: Canvas) {
        val cx = width - dp(82f)
        val cy = dp(70f)
        canvas.drawLine(cx - dp(74f), cy - dp(25f), cx + dp(72f), cy - dp(25f), blue)
        canvas.drawLine(cx, cy - dp(31f), cx, cy + dp(7f), blue)
        canvas.drawOval(cx - dp(25f), cy, cx + dp(25f), cy + dp(34f), blue)
        canvas.drawLine(cx + dp(23f), cy + dp(10f), cx + dp(61f), cy + dp(2f), blue)
        canvas.drawLine(cx + dp(61f), cy + dp(2f), cx + dp(70f), cy + dp(13f), blue)
        canvas.drawCircle(cx + dp(69f), cy + dp(11f), dp(7f), blue)
        canvas.drawLine(cx - dp(17f), cy + dp(34f), cx - dp(21f), cy + dp(43f), blue)
        canvas.drawLine(cx + dp(17f), cy + dp(34f), cx + dp(21f), cy + dp(43f), blue)
        canvas.drawLine(cx - dp(29f), cy + dp(43f), cx + dp(29f), cy + dp(43f), blue)
        canvas.drawCircle(cx, cy + dp(17f), dp(3f), amber)
    }

    private fun drawHelipad(canvas: Canvas) {
        val cx = width / 2f
        val cy = height - dp(76f)
        canvas.drawOval(cx - dp(118f), cy - dp(25f), cx + dp(118f), cy + dp(25f), amber)
        canvas.drawOval(cx - dp(92f), cy - dp(18f), cx + dp(92f), cy + dp(18f), amber)
        canvas.drawLine(cx - dp(150f), cy + dp(40f), cx + dp(150f), cy + dp(40f), blue)
        path.reset()
        path.moveTo(cx - dp(28f), cy - dp(13f))
        path.lineTo(cx - dp(28f), cy + dp(13f))
        path.moveTo(cx + dp(28f), cy - dp(13f))
        path.lineTo(cx + dp(28f), cy + dp(13f))
        path.moveTo(cx - dp(28f), cy)
        path.lineTo(cx + dp(28f), cy)
        canvas.drawPath(path, amber)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
