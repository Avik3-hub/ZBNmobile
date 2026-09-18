package com.example.zbnreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.json.JSONObject

class Mi171PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class Animation(val row: Int, val durationsMs: IntArray)

    private val atlas: Bitmap
    private val animations: Map<String, Animation>
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private var animationName = "idle"
    private var animation: Animation
    private var frame = 0
    private var repeat = true

    init {
        atlas = context.assets.open("mi171/mi171.png").use(BitmapFactory::decodeStream)
        animations = context.assets.open("mi171/animations.json").bufferedReader().use { reader ->
            val root = JSONObject(reader.readText()).getJSONObject("animations")
            root.keys().asSequence().associateWith { name ->
                val item = root.getJSONObject(name)
                val durations = item.getJSONArray("durationsMs")
                Animation(item.getInt("row"), IntArray(durations.length()) { durations.getInt(it) })
            }
        }
        animation = requireNotNull(animations[animationName])
        contentDescription = "Анимированный помощник Ми-171"
        isClickable = true
        setOnClickListener { play("wave", repeat = false) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleNextFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(nextFrame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellWidth = 192
        val cellHeight = 208
        val left = frame * cellWidth
        val top = animation.row * cellHeight
        source.set(left, top, left + cellWidth, top + cellHeight)

        val scale = minOf(width / cellWidth.toFloat(), height / cellHeight.toFloat())
        val drawWidth = cellWidth * scale
        val drawHeight = cellHeight * scale
        destination.set(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f,
            (width + drawWidth) / 2f,
            (height + drawHeight) / 2f
        )
        canvas.drawBitmap(atlas, source, destination, paint)
    }

    fun play(name: String, repeat: Boolean = true) {
        val next = animations[name] ?: return
        removeCallbacks(nextFrame)
        animationName = name
        animation = next
        frame = 0
        this.repeat = repeat
        invalidate()
        if (isAttachedToWindow) scheduleNextFrame()
    }

    private val nextFrame = Runnable {
        if (!isAttachedToWindow) return@Runnable
        if (frame + 1 < animation.durationsMs.size) {
            frame++
        } else if (repeat) {
            frame = 0
        } else {
            animationName = "idle"
            animation = requireNotNull(animations[animationName])
            frame = 0
            repeat = true
        }
        invalidate()
        scheduleNextFrame()
    }

    private fun scheduleNextFrame() {
        postDelayed(nextFrame, animation.durationsMs[frame].toLong())
    }
}
