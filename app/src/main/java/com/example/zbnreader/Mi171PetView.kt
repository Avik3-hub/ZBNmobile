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
import androidx.core.view.doOnLayout
import org.json.JSONObject
import kotlin.math.sin
import kotlin.math.cos

class Mi171PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class Animation(val row: Int, val durationsMs: IntArray)

    private val greetingAtlas: Bitmap
    private val greetingFrames = arrayOf(
        Rect(23,88,417,432),
        Rect(447,87,838,432),
        Rect(865,86,1258,432),
        Rect(1284,86,1676,432),
        Rect(24,507,417,855),
        Rect(447,507,839,855),
        Rect(865,507,1258,854),
        Rect(1286,507,1678,855)
    )
    private val idleAtlas: Bitmap
    // Visible frame bounds in the generated sheet (transparent gutters differ).
    private val idleFrames = arrayOf(
        Rect(25,94,418,429), Rect(448,93,838,429),
        Rect(869,94,1261,429), Rect(1290,93,1681,429),
        Rect(25,510,418,847), Rect(448,510,839,847),
        Rect(869,510,1261,847), Rect(1290,510,1682,847)
    )
    private val idleDurations = longArrayOf(1700,120,60,60,90,60,60,400)
    private val idleCycle = idleDurations.sum()
    private val idleDestination = RectF()
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
    private val destination = RectF()
    private val positionPrefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    private var animationName = "idle"
    private var animation: Animation
    private var frame = 0
    private var repeat = true
    private var frameStartedAt = SystemClock.uptimeMillis()
    private var motionStartedAt = SystemClock.uptimeMillis()
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var rotorSpinning = false
    private var busy = false
    private var rotorAngle = 0f
    private var rotorStartedAt = 0L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        atlas = context.assets.open("mi171/mi171.png").use(BitmapFactory::decodeStream)
        idleAtlas = context.assets.open("mi171/idle_v2.png").use(BitmapFactory::decodeStream)
        greetingAtlas = context.assets.open("mi171/greeting_v2.png").use(BitmapFactory::decodeStream)
        val assetAnimations = context.assets.open("mi171/animations.json").bufferedReader().use { reader ->
            val root = JSONObject(reader.readText()).getJSONObject("animations")
            root.keys().asSequence().associateWith { name ->
                val item = root.getJSONObject(name)
                val durations = item.getJSONArray("durationsMs")
                Animation(item.getInt("row"), IntArray(durations.length()) { durations.getInt(it) })
            }
        }
        animations = assetAnimations + ("wave" to Animation(0,
            intArrayOf(100, 110, 130, 120, 300, 130, 170, 140)))
        animation = requireNotNull(animations[animationName])
        contentDescription = "Анимированный помощник Ми-171"
        isClickable = true
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                restorePosition()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        doOnLayout { restorePosition() }
        refreshPlayback()
    }

    fun setPetEnabled(enabled: Boolean) {
        // Keep the measured size and FrameLayout gravity anchor while hidden.
        // GONE removes them from layout, invalidating drag translations.
        visibility = if (enabled) VISIBLE else INVISIBLE
        if (enabled) {
            doOnLayout {
                restorePosition()
                refreshPlayback()
                invalidate()
            }
        } else {
            dragging = false
            rotorSpinning = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        refreshPlayback()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        refreshPlayback()
    }

    fun setBusy(value: Boolean) {
        if (value && !busy && !rotorSpinning) rotorStartedAt = SystemClock.uptimeMillis()
        busy = value
        invalidate()
    }

    private fun refreshPlayback() {
        removeCallbacks(animationTick)
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            frameStartedAt = SystemClock.uptimeMillis()
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
        if (rotorSpinning || busy) {
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
        } else if (animationName == "idle" || animationName == "waiting" || animationName == "wave") {
            drawSmoothRestFrame(canvas)
        } else {
            canvas.drawBitmap(atlas, source, destination, paint)
        }
    }

    private fun drawSmoothRestFrame(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var elapsed = (now - motionStartedAt) % idleCycle
        var index = 0
        while (index < idleDurations.lastIndex && elapsed >= idleDurations[index]) {
            elapsed -= idleDurations[index]
            index++
        }
        // Same visible extent for every frame: no gutter-induced jumps.
        val unit = destination.height() / 208f
        idleDestination.set(destination.left + 5f*unit, destination.top + 23f*unit,
            destination.left + 187f*unit, destination.top + 189f*unit)
        if (animationName == "wave" && frame in 1..6) {
            canvas.drawBitmap(greetingAtlas, greetingFrames[frame], idleDestination, paint)
        } else {
            // Greeting starts/ends with the exact resting image, at identical size.
            val idleIndex = if (animationName == "wave") 0 else index
            canvas.drawBitmap(idleAtlas, idleFrames[idleIndex], idleDestination, paint)
        }
    }

    fun setSmallSize(small: Boolean) {
        val ratio = if (small) 2f / 3f else 1f
        val newWidth = (96f * resources.displayMetrics.density * ratio).toInt()
        val newHeight = (104f * resources.displayMetrics.density * ratio).toInt()
        val params = layoutParams ?: return
        if (params.width == newWidth && params.height == newHeight) return
        params.width = newWidth
        params.height = newHeight
        layoutParams = params
        // Layout listener restores the normalized position using the new size.
    }

    // Coordinates are in the original 192 x 208 sprite cell. Rotate in the
    // rotor's horizontal plane FIRST, then project its depth to screen Y.
    private fun drawProjectedRotor(canvas: Canvas) {
        val hubX = 98f
        val hubY = 46f
        val depthScale = 0.19f
        val phase = Math.toRadians(rotorAngle.toDouble())

        fun projectedPoint(angle: Double, radius: Float, chord: Float = 0f): Pair<Float, Float> {
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            return Pair(
                hubX + radius * c - chord * s,
                hubY + depthScale * (radius * s + chord * c)
            )
        }

        // Fixed mast and the non-rotating lower half of the swashplate.
        rotorPaint.style = Paint.Style.STROKE
        rotorPaint.strokeWidth = 5f
        rotorPaint.color = Color.rgb(96, 108, 118)
        canvas.drawLine(hubX, hubY + 3f, hubX, 68f, rotorPaint)
        rotorPaint.style = Paint.Style.FILL
        rotorPaint.color = Color.rgb(72, 82, 91)
        canvas.save()
        canvas.rotate(-4f, hubX, hubY + 13f)
        canvas.drawOval(hubX - 12f, hubY + 10f, hubX + 12f, hubY + 16f, rotorPaint)
        rotorPaint.style = Paint.Style.STROKE
        rotorPaint.strokeWidth = 1.2f
        rotorPaint.color = Color.rgb(184, 193, 199)
        canvas.drawOval(hubX - 12f, hubY + 10f, hubX + 12f, hubY + 16f, rotorPaint)
        canvas.restore()

        // Upper swashplate rotates with the hub. Its five pitch links terminate
        // at the blade grips and therefore follow the same phase as the rotor.
        rotorPaint.style = Paint.Style.FILL
        rotorPaint.color = Color.rgb(135, 145, 153)
        canvas.drawOval(hubX - 10f, hubY + 7f, hubX + 10f, hubY + 12f, rotorPaint)

        fun drawBladeAndGrip(angle: Double, front: Boolean) {
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            fun vertex(radius: Float, chord: Float, first: Boolean = false) {
                val (px, py) = projectedPoint(angle, radius, chord)
                if (first) bladePath.moveTo(px, py) else bladePath.lineTo(px, py)
            }

            // The hub sleeve and articulated grip occupy the inner radius.
            val armStart = projectedPoint(angle, 5f)
            val grip = projectedPoint(angle, 18f)
            rotorPaint.style = Paint.Style.STROKE
            rotorPaint.strokeCap = Paint.Cap.ROUND
            rotorPaint.strokeWidth = 4.2f
            rotorPaint.color = if (front) Color.rgb(121, 132, 140) else Color.rgb(87, 98, 107)
            canvas.drawLine(armStart.first, armStart.second, grip.first, grip.second, rotorPaint)
            rotorPaint.style = Paint.Style.FILL
            rotorPaint.color = Color.rgb(181, 190, 196)
            canvas.drawOval(grip.first - 2.7f, grip.second - 1.8f,
                grip.first + 2.7f, grip.second + 1.8f, rotorPaint)

            // Pitch link: lower end moves around the rotating swashplate, upper
            // end follows the corresponding grip. A slight sideways offset
            // keeps the rod visible next to the sleeve.
            val lower = projectedPoint(angle, 7.5f, 1.5f)
            val upper = projectedPoint(angle, 15f, 2.5f)
            rotorPaint.style = Paint.Style.STROKE
            rotorPaint.strokeCap = Paint.Cap.ROUND
            rotorPaint.strokeWidth = 1.15f
            rotorPaint.color = Color.rgb(218, 184, 92)
            canvas.drawLine(lower.first, lower.second + 9f,
                upper.first, upper.second + 1f, rotorPaint)

            // Blade root begins after the articulated grip, not at the mast.
            bladePath.reset()
            vertex(16f, -2f, true)
            vertex(84f, -3f)
            vertex(86f, 2f)
            vertex(21f, 4f)
            vertex(16f, 2f)
            bladePath.close()
            rotorPaint.style = Paint.Style.FILL
            rotorPaint.color = if (front) Color.rgb(66, 75, 83) else Color.rgb(47, 55, 64)
            canvas.drawPath(bladePath, rotorPaint)
            rotorPaint.style = Paint.Style.STROKE
            rotorPaint.strokeCap = Paint.Cap.BUTT
            rotorPaint.strokeWidth = 0.65f
            rotorPaint.color = Color.rgb(167, 177, 185)
            canvas.drawPath(bladePath, rotorPaint)
        }

        // Rear blades, central hub, then front blades preserve depth ordering.
        for (front in listOf(false, true)) {
            for (blade in 0 until 5) {
                val angle = phase + blade * 2.0 * Math.PI / 5.0
                if ((sin(angle) >= 0.0) != front) continue
                drawBladeAndGrip(angle, front)
            }
            if (!front) {
                // Rotating hub body and cap sit between rear and front grips.
                rotorPaint.style = Paint.Style.FILL
                rotorPaint.color = Color.rgb(99, 110, 119)
                canvas.drawOval(hubX - 8f, hubY - 4.5f, hubX + 8f, hubY + 4.5f, rotorPaint)
                rotorPaint.color = Color.rgb(193, 201, 206)
                canvas.drawOval(hubX - 5f, hubY - 3f, hubX + 5f, hubY + 2.5f, rotorPaint)
            }
        }
        rotorPaint.style = Paint.Style.FILL
        rotorPaint.color = Color.rgb(213, 218, 221)
        canvas.drawOval(hubX - 3.2f, hubY - 3.5f, hubX + 3.2f, hubY + 1f, rotorPaint)
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
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) postOnAnimation(animationTick)
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
            if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
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
            if (rotorSpinning || busy) {
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
            if (animationName == "wave") motionStartedAt = SystemClock.uptimeMillis()
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
