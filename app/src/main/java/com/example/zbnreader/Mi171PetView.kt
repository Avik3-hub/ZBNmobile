package com.example.zbnreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
    // Remove only the painted tail blades from the fixed body during dragging.
    // Coordinates are in the first 192 x 208 idle cell.
    private val tailBladeMask = Path().apply {
        moveTo(5f, 71f); lineTo(25f, 70f); lineTo(27f, 81f)
        lineTo(5f, 84f); close()
        moveTo(20f, 79f); lineTo(28f, 79f); lineTo(28f, 106f)
        lineTo(18f, 106f); close()
        moveTo(24f, 70f); lineTo(27f, 61f); lineTo(34f, 63f)
        lineTo(30f, 73f); close()
    }
    private val rotorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val animations: Map<String, Animation>
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val nextSource = Rect()
    private val addBlend = PorterDuffXfermode(PorterDuff.Mode.ADD)
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
            canvas.save()
            canvas.translate(destination.left, destination.top + bob)
            canvas.scale(scale, scale)
            canvas.save()
            canvas.clipOutPath(tailBladeMask)
            canvas.drawBitmap(atlas, source, RectF(0f, 64f, 192f, 208f), paint)
            canvas.restore()
            drawTailRotor(canvas, elapsed)
            drawProjectedRotor(canvas)
            canvas.restore()
        } else if (animationName == "idle" || animationName == "waiting") {
            drawSmoothRestFrame(canvas)
        } else {
            canvas.drawBitmap(atlas, source, destination, paint)
        }
    }

    private fun drawSmoothRestFrame(canvas: Canvas) {
        val nextFrame = if (frame < animation.durationsMs.lastIndex) frame + 1
            else if (repeat) 0 else frame
        val elapsed = SystemClock.uptimeMillis() - frameStartedAt
        val progress = (elapsed.toFloat() / animation.durationsMs[frame]).coerceIn(0f, 1f)
        // Smoothstep has zero velocity at both ends: no abrupt blend starts.
        val blend = progress * progress * (3f - 2f * progress)
        val nextAlpha = (blend * 255f).toInt()
        nextSource.set(nextFrame * 192, animation.row * 208,
            (nextFrame + 1) * 192, (animation.row + 1) * 208)

        // Add weighted premultiplied pixels inside an isolated layer. Ordinary
        // SRC_OVER leaves the old silhouette behind and dims opaque pixels.
        val layer = canvas.saveLayer(destination, null)
        paint.alpha = 255 - nextAlpha
        canvas.drawBitmap(atlas, source, destination, paint)
        paint.alpha = nextAlpha
        paint.xfermode = addBlend
        canvas.drawBitmap(atlas, nextSource, destination, paint)
        paint.xfermode = null
        paint.alpha = 255
        canvas.restoreToCount(layer)
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

    private fun drawTailRotor(canvas: Canvas, elapsed: Double) {
        val hubX = 26f
        val hubY = 76f
        // Vertical rotor plane: narrow horizontal axis, full vertical axis.
        // A 12-degree screen tilt follows the tail mast in the source sprite.
        // This projection is deliberately different from the main rotor plane.
        val phase = (elapsed * 3.2 * 2.0 * Math.PI) % (2.0 * Math.PI)
        for (blade in 0 until 3) {
            val angle = phase + blade * 2.0 * Math.PI / 3.0
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            fun section(inner: Float, outer: Float, color: Int) {
                fun vertex(r: Float, chord: Float, first: Boolean = false) {
                    val u = r * c - chord * s
                    val v = r * s + chord * c
                    val px = hubX + 0.489f * u + 0.208f * v
                    val py = hubY + 0.104f * u - 0.978f * v
                    if (first) bladePath.moveTo(px, py) else bladePath.lineTo(px, py)
                }
                bladePath.reset()
                vertex(inner, -2f, true)
                vertex(outer, -2.8f)
                vertex(outer, 2.8f)
                vertex(inner, 2f)
                bladePath.close()
                rotorPaint.style = Paint.Style.FILL
                rotorPaint.color = color
                canvas.drawPath(bladePath, rotorPaint)
            }
            section(3f, 27f, Color.rgb(222, 228, 231))
            section(22f, 27f, Color.rgb(217, 54, 61))
        }
        // Stationary gearbox and hub cover the roots of all three blades.
        rotorPaint.color = Color.rgb(40, 69, 94)
        canvas.drawOval(hubX - 3f, hubY - 4f, hubX + 3f, hubY + 4f, rotorPaint)
        rotorPaint.color = Color.rgb(163, 177, 187)
        canvas.drawCircle(hubX, hubY, 1.4f, rotorPaint)
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
            // Preserve elapsed time rather than discarding the remainder at
            // each frame boundary. Skip complete loops after a long pause.
            if (repeat) {
                val cycleMs = animation.durationsMs.sum().toLong()
                frameStartedAt += ((now - frameStartedAt) / cycleMs) * cycleMs
            }
            while (now - frameStartedAt >= animation.durationsMs[frame]) {
                frameStartedAt += animation.durationsMs[frame]
                advanceFrame()
                if (repeat) {
                    val cycleMs = animation.durationsMs.sum().toLong()
                    frameStartedAt += ((now - frameStartedAt) / cycleMs) * cycleMs
                }
            }
            if (rotorSpinning) {
                // 1.6 revolutions/sec (twice the previous speed), independent of refresh rate.
                rotorAngle = (((now - rotorStartedAt) % 625L) * 360f / 625f)
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
