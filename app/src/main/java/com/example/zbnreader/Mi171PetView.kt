package com.example.zbnreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
    private val positionPrefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    private var animationName = "idle"
    private var animation: Animation
    private var frame = 0
    private var repeat = true
    private var frameStartedAt = SystemClock.uptimeMillis()
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

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
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            restorePosition()
            postOnAnimation(animationTick)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animationTick)
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
        paint.alpha = 255
        canvas.drawBitmap(atlas, source, destination, paint)

        // Последний idle-кадр мягко растворяется в первом: место стыка цикла
        // больше не выглядит внезапным обрывом.
        if (animationName == "idle" && repeat && frame == animation.durationsMs.lastIndex) {
            val duration = animation.durationsMs[frame]
            val elapsed = (SystemClock.uptimeMillis() - frameStartedAt).toInt()
            val blendDuration = minOf(180, duration)
            val blend = ((elapsed - (duration - blendDuration)).toFloat() / blendDuration)
                .coerceIn(0f, 1f)
            if (blend > 0f) {
                source.set(0, top, cellWidth, top + cellHeight)
                paint.alpha = (255 * blend).toInt()
                canvas.drawBitmap(atlas, source, destination, paint)
                paint.alpha = 255
            }
        }
    }

    fun play(name: String, repeat: Boolean = true) {
        val next = animations[name] ?: return
        removeCallbacks(animationTick)
        animationName = name
        animation = next
        frame = 0
        frameStartedAt = SystemClock.uptimeMillis()
        this.repeat = repeat
        invalidate()
        if (isAttachedToWindow) postOnAnimation(animationTick)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = x
                downY = y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (dx * dx + dy * dy) > touchSlop * touchSlop) dragging = true
                if (dragging) {
                    val container = parent as? View ?: return true
                    x = (downX + dx).coerceIn(0f, (container.width - width).coerceAtLeast(0).toFloat())
                    y = (downY + dy).coerceIn(0f, (container.height - height).coerceAtLeast(0).toFloat())
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) savePosition() else performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        play("wave", repeat = false)
        return true
    }

    private val animationTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val now = SystemClock.uptimeMillis()
            if (now - frameStartedAt >= animation.durationsMs[frame]) {
                advanceFrame()
                frameStartedAt = now
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun advanceFrame() {
        if (!isAttachedToWindow) return
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
    }

    private fun savePosition() {
        val container = parent as? View ?: return
        val availableX = (container.width - width).coerceAtLeast(1)
        val availableY = (container.height - height).coerceAtLeast(1)
        positionPrefs.edit()
            .putFloat("mi171_pet_x", x / availableX)
            .putFloat("mi171_pet_y", y / availableY)
            .apply()
    }

    private fun restorePosition() {
        if (!positionPrefs.contains("mi171_pet_x")) return
        val container = parent as? View ?: return
        x = positionPrefs.getFloat("mi171_pet_x", 1f).coerceIn(0f, 1f) * (container.width - width).coerceAtLeast(0)
        y = positionPrefs.getFloat("mi171_pet_y", 1f).coerceIn(0f, 1f) * (container.height - height).coerceAtLeast(0)
    }
}
