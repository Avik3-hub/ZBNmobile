package com.example.zbnreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.json.JSONObject
import kotlin.math.sin
import kotlin.math.cos

class Mi171PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class Animation(val row: Int, val durationsMs: IntArray)

    private val atlas: Bitmap
    private val bladePath = Path()
    private val rotorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private var rotorSpinning = false
    private var rotorAngle = 0f
    private var rotorStartedAt = 0L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        atlas = context.assets.open("mi171/mi171.png").use(BitmapFactory::decodeStream)
        val assetAnimations = context.assets.open("mi171/animations.json").bufferedReader().use { reader ->
            val root = JSONObject(reader.readText()).getJSONObject("animations")
            root.keys().asSequence().associateWith { name ->
                val item = root.getJSONObject(name)
                val durations = item.getJSONArray("durationsMs")
                Animation(item.getInt("row"), IntArray(durations.length()) { durations.getInt(it) })
            }
        }
        animations = assetAnimations
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
        if (rotorSpinning) {
            // Корпус остаётся неподвижным, ротор рисуется отдельным слоем.
            val rotorHeight = 64
            source.set(0, rotorHeight, cellWidth, cellHeight)
            val elapsed = (SystemClock.uptimeMillis() - rotorStartedAt) / 1000.0
            val bob = sin(elapsed * Math.PI * 12.0).toFloat() * scale * 0.6f
            val bodyTop = destination.top + destination.height() * rotorHeight / cellHeight + bob
            val bodyDestination = RectF(destination.left, bodyTop, destination.right, destination.bottom + bob)
            canvas.drawBitmap(atlas, source, bodyDestination, paint)

            canvas.save()
            canvas.translate(destination.left, destination.top + bob)
            canvas.scale(scale, scale)
            drawProjectedRotor(canvas)
            canvas.restore()
        } else {
            canvas.drawBitmap(atlas, source, destination, paint)
        }

        // Последний idle-кадр мягко растворяется в первом: место стыка цикла
        // больше не выглядит внезапным обрывом.
        if (!rotorSpinning && animationName == "idle" && repeat && frame == animation.durationsMs.lastIndex) {
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

    // Coordinates are in the original 192 x 208 sprite cell. Rotate in the
    // rotor's horizontal plane FIRST, then project its depth to screen Y.
    private fun drawProjectedRotor(canvas: Canvas) {
        val hubX = 98f
        val hubY = 46f
        val depthScale = 0.19f
        val phase = Math.toRadians(rotorAngle.toDouble())

        // Mast connects the stable body to the hub; it never rotates on screen.
        rotorPaint.style = Paint.Style.STROKE
        rotorPaint.strokeWidth = 4f
        rotorPaint.color = Color.rgb(124, 133, 142)
        canvas.drawLine(hubX, hubY, hubX, 68f, rotorPaint)

        // Rear blades, fixed hub, then front blades: correct depth ordering.
        for (front in listOf(false, true)) {
            for (blade in 0 until 5) {
                val angle = phase + blade * 2.0 * Math.PI / 5.0
                if ((sin(angle) >= 0.0) != front) continue
                val c = cos(angle).toFloat()
                val s = sin(angle).toFloat()
                fun vertex(radius: Float, chord: Float, first: Boolean = false) {
                    val px = hubX + radius * c - chord * s
                    val py = hubY + depthScale * (radius * s + chord * c)
                    if (first) bladePath.moveTo(px, py) else bladePath.lineTo(px, py)
                }
                bladePath.reset()
                vertex(7f, -2f, true)
                vertex(84f, -3f)
                vertex(86f, 2f)
                vertex(18f, 4f)
                vertex(7f, 2f)
                bladePath.close()
                rotorPaint.style = Paint.Style.FILL
                rotorPaint.color = if (front) Color.rgb(66, 75, 83) else Color.rgb(47, 55, 64)
                canvas.drawPath(bladePath, rotorPaint)
                rotorPaint.style = Paint.Style.STROKE
                rotorPaint.strokeWidth = 0.65f
                rotorPaint.color = Color.rgb(167, 177, 185)
                canvas.drawPath(bladePath, rotorPaint)
            }
            if (!front) {
                rotorPaint.style = Paint.Style.FILL
                rotorPaint.color = Color.rgb(172, 180, 186)
                canvas.drawOval(hubX - 6f, hubY - 3f, hubX + 6f, hubY + 3f, rotorPaint)
            }
        }
        rotorPaint.style = Paint.Style.FILL
        rotorPaint.color = Color.rgb(213, 218, 221)
        canvas.drawOval(hubX - 3f, hubY - 2f, hubX + 3f, hubY + 1f, rotorPaint)
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
                if (!dragging && (dx * dx + dy * dy) > touchSlop * touchSlop) {
                    dragging = true
                    rotorSpinning = true
                    rotorStartedAt = SystemClock.uptimeMillis()
                }
                if (dragging) {
                    val container = parent as? View ?: return true
                    x = (downX + dx).coerceIn(0f, (container.width - width).coerceAtLeast(0).toFloat())
                    y = (downY + dy).coerceIn(0f, (container.height - height).coerceAtLeast(0).toFloat())
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    savePosition()
                    rotorSpinning = false
                    play("idle")
                } else {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    rotorSpinning = false
                    play("idle")
                }
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
            if (rotorSpinning) {
                // 0.8 revolutions/sec, independent of 60/90/120 Hz displays.
                rotorAngle = (((now - rotorStartedAt) % 1250L) * 360f / 1250f)
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
