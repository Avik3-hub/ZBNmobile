package com.example.zbnreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class DocumentCropView(context: Context) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = 0x99000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8C7FA")
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4285F4")
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
    }
    private var bitmap: Bitmap? = null
    private val points = MutableList(4) { PointF() }
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var activePoint = -1

    fun setDocument(bitmap: Bitmap, corners: Array<org.opencv.core.Point>) {
        this.bitmap = bitmap
        corners.forEachIndexed { index, point ->
            points[index].set(point.x.toFloat(), point.y.toFloat())
        }
        invalidate()
    }

    fun documentCorners(): Array<org.opencv.core.Point> = points.map {
        org.opencv.core.Point(it.x.toDouble(), it.y.toDouble())
    }.toTypedArray()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        offsetX = (width - image.width * scale) / 2f
        offsetY = (height - image.height * scale) / 2f
        val destination = RectF(
            offsetX, offsetY,
            offsetX + image.width * scale,
            offsetY + image.height * scale
        )
        canvas.drawBitmap(image, null, destination, imagePaint)

        val path = screenPath()
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawRect(destination, shadePaint)
        canvas.restore()
        canvas.drawPath(path, linePaint)

        points.forEach {
            val x = offsetX + it.x * scale
            val y = offsetY + it.y * scale
            canvas.drawCircle(x, y, dp(11f), handlePaint)
            canvas.drawCircle(x, y, dp(11f), handleBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePoint = points.indices.minByOrNull { index ->
                    val x = offsetX + points[index].x * scale
                    val y = offsetY + points[index].y * scale
                    hypot((event.x - x).toDouble(), (event.y - y).toDouble())
                } ?: -1
                if (activePoint < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                updatePoint(activePoint, event.x, event.y, image)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePoint >= 0) updatePoint(activePoint, event.x, event.y, image)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePoint = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updatePoint(index: Int, screenX: Float, screenY: Float, image: Bitmap) {
        points[index].x = ((screenX - offsetX) / scale).coerceIn(0f, image.width - 1f)
        points[index].y = ((screenY - offsetY) / scale).coerceIn(0f, image.height - 1f)
        invalidate()
    }

    private fun screenPath() = Path().apply {
        points.forEachIndexed { index, point ->
            val x = offsetX + point.x * scale
            val y = offsetY + point.y * scale
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
