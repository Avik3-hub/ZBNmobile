package com.example.zbnreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.min

/** Non-interactive sibling overlay: bubbles and cable follow the draggable pet. */
class PetSpeechView(context: Context, private val pet: View) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 241, 250)
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private var message = ""
    private var until = 0L
    private var shownAt = 0L
    private var pending: String? = null
    private var enabled = true
    private var connected = false
    private var cableFrom = 0f
    private var cableTo = 0f
    private var cableStarted = 0L
    private var laptopUntil = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isShown || !enabled) return
            val now = SystemClock.uptimeMillis()
            if (pending != null && now - shownAt >= 1500L) {
                val next = pending!!
                pending = null
                display(next)
            }
            invalidate()
            if (connected || now < until || now < laptopUntil || pending != null) postOnAnimation(this)
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setHelperEnabled(value: Boolean) {
        enabled = value
        visibility = if (value) VISIBLE else INVISIBLE
        if (!value) { pending = null; message = ""; until = 0L }
        restart()
    }

    fun say(text: String, urgent: Boolean = false) {
        if (!enabled) return
        if (!urgent && message.isNotEmpty() && SystemClock.uptimeMillis() - shownAt < 1500L) {
            pending = text
        } else {
            pending = null
            display(text)
        }
        restart()
    }

    private fun display(text: String) {
        message = text
        shownAt = SystemClock.uptimeMillis()
        until = shownAt + 4500L
        announceForAccessibility(text)
    }

    private fun cablePosition(): Float {
        val t = ((SystemClock.uptimeMillis() - cableStarted) / 650f).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return cableFrom + (cableTo - cableFrom) * eased
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        cableFrom = cablePosition()
        cableTo = if (value) 1f else 0f
        cableStarted = SystemClock.uptimeMillis()
        laptopUntil = cableStarted + if (value) 5000L else 2000L
        connected = value
        restart()
    }

    private fun restart() {
        removeCallbacks(tick)
        if (isAttachedToWindow && isShown && enabled) postOnAnimation(tick)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); restart() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        restart()
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabled || !pet.isShown || width == 0) return
        val now = SystemClock.uptimeMillis()
        val pad = 10f * density
        if (connected || now < laptopUntil) {
            val w = min(44f * density, width / 4f)
            val h = w * .65f
            val x = (if (pet.x >= w + pad) pet.x - w - pad else pet.x + pet.width + pad)
                .coerceIn(pad, (width - w - pad).coerceAtLeast(pad))
            val y = (pet.y + pet.height * .6f).coerceIn(pad, (height - h - pad).coerceAtLeast(pad))
            val socketX = pet.x + pet.width * .52f
            val socketY = pet.y + pet.height * .65f
            val startX = x + w / 2f
            val startY = y + h
            val t = cablePosition()
            val endX = startX + (socketX - startX) * t
            val endY = startY + (socketY - startY) * t
            paint.color = Color.rgb(150, 189, 234)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            val cable = Path().apply {
                moveTo(startX, startY)
                quadTo(startX, min(height - pad, startY + 20f * density), endX, endY)
            }
            canvas.drawPath(cable, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(endX - 3f*density, endY - 2f*density,
                endX + 3f*density, endY + 2f*density, density, density, paint)
            paint.color = Color.rgb(92, 108, 125)
            canvas.drawRoundRect(x, y, x+w, y+h, 3f*density, 3f*density, paint)
            paint.color = Color.rgb(28, 54, 77)
            canvas.drawRect(x+3f*density, y+3f*density, x+w-3f*density, y+h-4f*density, paint)
            paint.color = Color.rgb(173, 187, 201)
            canvas.drawRoundRect(x-3f*density, y+h, x+w+3f*density, y+h+3f*density, density, density, paint)
        }
        if (now >= until || message.isEmpty()) return
        val bubbleWidth = min(220f * density, width - 2f * pad).coerceAtLeast(1f)
        val layout = StaticLayout.Builder.obtain(message, 0, message.length, textPaint,
            (bubbleWidth - 2f*pad).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        val h = layout.height + 2f*pad
        val x = (pet.x + pet.width/2f - bubbleWidth/2f).coerceIn(pad, (width-bubbleWidth-pad).coerceAtLeast(pad))
        val above = pet.y >= h + 2f*pad
        val y = (if (above) pet.y-h-pad else pet.y+pet.height+pad)
            .coerceIn(pad, (height-h-pad).coerceAtLeast(pad))
        paint.color = Color.rgb(40, 55, 74)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(x,y,x+bubbleWidth,y+h), 12f*density,12f*density,paint)
        val tipX = (pet.x+pet.width/2f).coerceIn(x+pad,x+bubbleWidth-pad)
        val edgeY = if (above) y+h else y
        val tail = Path().apply {
            moveTo(tipX-5f*density,edgeY)
            lineTo(tipX,edgeY + if (above) 7f*density else -7f*density)
            lineTo(tipX+5f*density,edgeY); close()
        }
        canvas.drawPath(tail,paint)
        canvas.save(); canvas.translate(x+pad,y+pad); layout.draw(canvas); canvas.restore()
    }
}
