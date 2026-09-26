package com.example.zbnreader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

class ZbnConnectionSceneView(context: Context) : View(context) {
    enum class Signal {
        NONE,
        REQUEST,
        RECEIVE,
        SUCCESS,
        ERROR
    }

    private val laptop = BitmapFactory.decodeResource(resources, R.drawable.zbn_laptop_scene)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 27, 36)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3.6f)
    }
    private val cableHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 178, 199)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(1.15f)
        alpha = 215
    }
    private val pulseGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pulseCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(164, 201, 255)
        textAlign = Paint.Align.CENTER
        textSize = sp(11.7f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 50, 69, 90)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 164, 255)
    }

    private val cablePath = Path()
    private val pathMeasure = PathMeasure()
    private var pulseAnimator: ValueAnimator? = null
    private var pulsePhase = 0f
    private var currentSignal = Signal.NONE
    private var message = "USB подключён\nОжидание ЗБН…"
    private var downloadProgress: Int? = null
    private var sceneEnabled = true
    private var usbConnected = false
    private var disconnectSequence = 0
    private var demoSequence = 0
    private var demoMode = false

    init {
        alpha = 0f
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setSceneEnabled(enabled: Boolean) {
        sceneEnabled = enabled
        if (!enabled) {
            cancelDemo()
            disconnectSequence++
            animate().cancel()
            pulseAnimator?.cancel()
            alpha = 0f
            visibility = INVISIBLE
        } else if (usbConnected) {
            showConnectedScene()
        }
    }

    fun setUsbConnected(connected: Boolean) {
        if (demoMode) cancelDemo()
        usbConnected = connected
        disconnectSequence++
        val sequence = disconnectSequence
        if (!sceneEnabled) return

        if (connected) {
            message = "USB подключён\nОжидание ЗБН…"
            downloadProgress = null
            currentSignal = Signal.NONE
            stopPulse()
            showConnectedScene()
        } else if (visibility == VISIBLE) {
            showStatus("Кабель отключён", Signal.ERROR)
            postDelayed({
                if (sequence == disconnectSequence && !usbConnected) hideScene()
            }, 900L)
        }
    }

    fun showStatus(text: String, signal: Signal = Signal.NONE, progress: Int? = null) {
        if (!sceneEnabled || (!usbConnected && !demoMode && signal != Signal.ERROR)) return
        message = text
        downloadProgress = progress?.coerceIn(0, 100)
        currentSignal = signal
        visibility = VISIBLE
        if (alpha < 1f) animate().alpha(1f).setDuration(240L).start()
        if (signal == Signal.NONE) stopPulse() else startPulse()
        invalidate()
    }

    fun showDownload(recordNumber: Int, percent: Int) {
        showStatus(
            "Скачивание №$recordNumber\n${percent.coerceIn(0, 100)}%",
            Signal.RECEIVE,
            percent
        )
    }

    fun playDemo(recordNumber: Int) {
        if (!sceneEnabled) return
        demoSequence++
        val sequence = demoSequence
        demoMode = true
        message = "USB подключён\nОжидание ЗБН…"
        downloadProgress = null
        currentSignal = Signal.NONE
        stopPulse()
        showConnectedScene()

        demoStep(sequence, 1_200L) {
            showStatus("Установка связи…", Signal.REQUEST)
        }
        demoStep(sequence, 2_600L) {
            showStatus("ЗБН не отвечает\nПовторная попытка…", Signal.ERROR)
        }
        demoStep(sequence, 4_000L) {
            showStatus("Повторный запрос…", Signal.REQUEST)
        }
        demoStep(sequence, 5_300L) {
            showStatus("ЗБН обнаружен\nACK получен", Signal.SUCCESS)
        }
        demoStep(sequence, 6_500L) {
            showStatus("Чтение оглавления…", Signal.RECEIVE)
        }
        for (percent in 0..100 step 5) {
            demoStep(sequence, 7_600L + (percent / 5) * 120L) {
                showDownload(recordNumber, percent)
            }
        }
        demoStep(sequence, 10_300L) {
            showStatus("Полёт №$recordNumber скачан", Signal.SUCCESS, 100)
        }
        demoStep(sequence, 12_000L) {
            demoMode = false
            if (usbConnected) {
                setUsbConnected(true)
            } else {
                hideScene()
            }
        }
    }

    private fun demoStep(sequence: Int, delayMs: Long, action: () -> Unit) {
        postDelayed({
            if (demoMode && sequence == demoSequence) action()
        }, delayMs)
    }

    private fun cancelDemo() {
        demoSequence++
        demoMode = false
    }

    private fun showConnectedScene() {
        visibility = VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(320L).setInterpolator(DecelerateInterpolator()).start()
        invalidate()
    }

    private fun hideScene() {
        animate().cancel()
        animate().alpha(0f).setDuration(260L).withEndAction {
            if (!usbConnected) {
                visibility = INVISIBLE
                stopPulse()
            }
        }.start()
    }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulsePhase = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE || width == 0 || height == 0) return

        val laptopWidth = min(dp(150f), width * 0.435f)
        val laptopHeight = laptopWidth * laptop.height / laptop.width.toFloat()
        val laptopRect = RectF(
            0f,
            height - laptopHeight + dp(8f),
            laptopWidth,
            height + dp(8f)
        )

        buildCablePath(laptopRect)
        canvas.drawPath(cablePath, cablePaint)
        canvas.drawPath(cablePath, cableHighlightPaint)
        drawPulse(canvas)
        canvas.drawBitmap(laptop, null, laptopRect, bitmapPaint)
        drawLaptopScreen(canvas, laptopRect)
    }

    private fun buildCablePath(laptopRect: RectF) {
        val startX = laptopRect.right - dp(1f)
        val startY = laptopRect.bottom - laptopRect.height() * 0.12f
        val endX = width * 0.69f
        val endY = height * 0.69f
        cablePath.reset()
        cablePath.moveTo(startX, startY)
        cablePath.cubicTo(
            startX + dp(34f), startY + dp(5f),
            endX - dp(34f), endY + dp(14f),
            endX, endY
        )
    }

    private fun drawPulse(canvas: Canvas) {
        if (currentSignal == Signal.NONE || pulseAnimator == null) return
        val color = when (currentSignal) {
            Signal.REQUEST, Signal.RECEIVE -> Color.rgb(65, 168, 255)
            Signal.SUCCESS -> Color.rgb(62, 224, 145)
            Signal.ERROR -> Color.rgb(255, 76, 84)
            Signal.NONE -> return
        }
        pathMeasure.setPath(cablePath, false)
        val length = pathMeasure.length
        val forward = currentSignal == Signal.REQUEST
        val center = if (forward) pulsePhase * length else (1f - pulsePhase) * length
        val travelDirection = if (forward) 1f else -1f
        val position = FloatArray(2)
        val tangent = FloatArray(2)

        repeat(5) { index ->
            val distance = center - travelDirection * dp(8f) * index
            if (distance !in 0f..length || !pathMeasure.getPosTan(distance, position, tangent)) return@repeat
            val normalX = -tangent[1]
            val normalY = tangent[0]
            val shimmer = if (index == 0) 0f else kotlin.math.sin(pulsePhase * 18f + index) * dp(1.2f)
            val x = position[0] + normalX * shimmer
            val y = position[1] + normalY * shimmer
            val radius = dp(2.4f - index * 0.3f)
            val alpha = (255 - index * 38).coerceAtLeast(80)

            pulseGlowPaint.color = color
            pulseGlowPaint.alpha = (alpha * 0.28f).toInt()
            canvas.drawCircle(x, y, radius * 2.8f, pulseGlowPaint)

            pulsePaint.color = color
            pulsePaint.alpha = alpha
            canvas.drawCircle(x, y, radius, pulsePaint)

            if (index == 0) {
                pulseCorePaint.alpha = 235
                canvas.drawCircle(x, y, dp(0.9f), pulseCorePaint)
            }
        }
    }

    private fun drawLaptopScreen(canvas: Canvas, laptopRect: RectF) {
        val screen = RectF(
            laptopRect.left + laptopRect.width() * 0.145f,
            laptopRect.top + laptopRect.height() * 0.105f,
            laptopRect.left + laptopRect.width() * 0.865f,
            laptopRect.top + laptopRect.height() * 0.685f
        )
        val color = when (currentSignal) {
            Signal.SUCCESS -> Color.rgb(94, 232, 164)
            Signal.ERROR -> Color.rgb(255, 108, 112)
            else -> Color.rgb(164, 201, 255)
        }
        textPaint.color = color
        progressPaint.color = if (currentSignal == Signal.ERROR) Color.rgb(255, 90, 95) else color

        val lines = wrapText(message, screen.width() - dp(10f))
        val lineHeight = dp(13.5f)
        val textBlockHeight = lines.size * lineHeight
        var baseline = screen.centerY() - textBlockHeight / 2f + lineHeight * 0.8f
        if (downloadProgress != null) baseline -= dp(4f)
        lines.take(if (downloadProgress == null) 3 else 2).forEach { line ->
            canvas.drawText(line, screen.centerX(), baseline, textPaint)
            baseline += lineHeight
        }

        downloadProgress?.let { progress ->
            val track = RectF(
                screen.left + dp(9f),
                screen.bottom - dp(9f),
                screen.right - dp(9f),
                screen.bottom - dp(5.5f)
            )
            canvas.drawRoundRect(track, dp(2f), dp(2f), progressTrackPaint)
            val fill = RectF(track.left, track.top, track.left + track.width() * progress / 100f, track.bottom)
            canvas.drawRoundRect(fill, dp(2f), dp(2f), progressPaint)
        }
    }

    private fun wrapText(text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            var current = ""
            paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (textPaint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                    current = candidate
                } else {
                    lines += current
                    current = word
                }
            }
            if (current.isNotEmpty()) lines += current
        }
        return lines.ifEmpty { listOf("") }
    }

    override fun onDetachedFromWindow() {
        cancelDemo()
        animate().cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
