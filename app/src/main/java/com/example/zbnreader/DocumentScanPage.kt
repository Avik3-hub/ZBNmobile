package com.example.zbnreader

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class DocumentScanPage(context: Context) : LinearLayout(context) {
    private val backgroundColor = Color.parseColor("#080A0D")
    private val surfaceColor = Color.parseColor("#14181D")
    private val fieldColor = Color.parseColor("#20262D")
    private val borderColor = Color.parseColor("#343C45")
    private val accentColor = Color.parseColor("#A8C7FA")
    private val accentTextColor = Color.parseColor("#071526")
    private val amberColor = Color.parseColor("#F1B45B")
    private val textColor = Color.parseColor("#F2F5F7")
    private val mutedColor = Color.parseColor("#89929C")
    private val blueprintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#21364C")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f).toFloat()
    }
    private val runwayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#543A18")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f).toFloat()
    }

    private val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    private val tailValue = TextView(context)
    private val surnameInput = EditText(context)
    private val fileNamePreview = TextView(context)
    private val megapixels = intArrayOf(1, 2, 3, 5, 8, 12)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(dp(16), dp(14), dp(16), dp(12))
        setBackgroundColor(backgroundColor)

        addView(TextView(context).apply {
            text = "ДОКУМЕНТЫ ЭКИПАЖА"
            textSize = 10f
            letterSpacing = 0.09f
            setTextColor(amberColor)
        }, fullWidth(bottom = 3))

        addView(TextView(context).apply {
            text = "Паспорт БУР-1"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }, fullWidth(bottom = 3))

        addView(TextView(context).apply {
            text = "Съёмка, выравнивание и подготовка документа"
            textSize = 12f
            setTextColor(mutedColor)
        }, fullWidth(bottom = 14))

        val aircraftCard = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = rounded(surfaceColor, 18f, borderColor)
        }
        aircraftCard.addView(label("ПОСЛЕДНИЙ БОРТ", amberColor))
        tailValue.apply {
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(0, dp(5), 0, 0)
        }
        aircraftCard.addView(tailValue, fullWidth())
        addView(aircraftCard, fullWidth(bottom = 12))

        val settingsCard = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(14))
            background = rounded(surfaceColor, 20f, borderColor)
        }

        settingsCard.addView(label("ФАМИЛИЯ ДЛЯ ИМЕНИ ФАЙЛА"), fullWidth(bottom = 6))
        surnameInput.apply {
            setText(prefs.getString(DocumentFileName.PREF_SURNAME, "НАГИБИН"))
            textSize = 16f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            hint = "ФАМИЛИЯ"
            setSingleLine(true)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(fieldColor, 14f, borderColor)
        }
        settingsCard.addView(surnameInput, fullWidth(bottom = 14))

        settingsCard.addView(label("РАЗРЕШЕНИЕ СНИМКА"), fullWidth(bottom = 6))
        val resolutionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                megapixels.map { "$it Мп" }
            )
            val saved = prefs.getInt(DocumentFileName.PREF_MEGAPIXELS, 5)
            setSelection(megapixels.indexOf(saved).coerceAtLeast(0))
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(fieldColor, 14f, borderColor)
        }
        settingsCard.addView(resolutionSpinner, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply {
            bottomMargin = dp(14)
        })

        settingsCard.addView(label("БУДЕТ СОХРАНЕНО КАК"), fullWidth(bottom = 6))
        fileNamePreview.apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(fieldColor, 14f, borderColor)
        }
        settingsCard.addView(fileNamePreview, fullWidth())
        addView(settingsCard, fullWidth(bottom = 14))

        val scanButton = Button(context).apply {
            text = "СФОТОГРАФИРОВАТЬ ДОКУМЕНТ"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentTextColor)
            minHeight = 0
            minimumHeight = 0
            background = rounded(accentColor, 20f)
            setOnClickListener {
                val surname = DocumentFileName.normalizeSurname(surnameInput.text.toString())
                val mp = megapixels[resolutionSpinner.selectedItemPosition]
                prefs.edit()
                    .putString(DocumentFileName.PREF_SURNAME, surname)
                    .putInt(DocumentFileName.PREF_MEGAPIXELS, mp)
                    .apply()
                context.startActivity(Intent(context, DocumentScanActivity::class.java).apply {
                    putExtra(DocumentScanActivity.EXTRA_TAIL, currentTail())
                    putExtra(DocumentScanActivity.EXTRA_SURNAME, surname)
                    putExtra(DocumentScanActivity.EXTRA_MEGAPIXELS, mp)
                })
            }
        }
        addView(scanButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(62)))

        surnameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val helicopterX = width - dp(78).toFloat()
        val helicopterY = dp(54).toFloat()
        canvas.drawLine(helicopterX - dp(58), helicopterY, helicopterX + dp(58), helicopterY, blueprintPaint)
        canvas.drawLine(helicopterX, helicopterY - dp(8), helicopterX, helicopterY + dp(24), blueprintPaint)
        canvas.drawOval(
            helicopterX - dp(20), helicopterY + dp(15),
            helicopterX + dp(20), helicopterY + dp(43), blueprintPaint
        )
        canvas.drawLine(
            helicopterX + dp(18), helicopterY + dp(23),
            helicopterX + dp(53), helicopterY + dp(16), blueprintPaint
        )

        val padY = height - dp(56).toFloat()
        canvas.drawOval(
            width / 2f - dp(116), padY - dp(20),
            width / 2f + dp(116), padY + dp(20), runwayPaint
        )
        canvas.drawLine(dp(20).toFloat(), padY + dp(30), width - dp(20).toFloat(), padY + dp(30), blueprintPaint)
    }

    fun refresh() {
        val tail = currentTail()
        tailValue.text = if (tail.isEmpty()) "Не определён" else "RA-$tail"
        updatePreview()
    }

    private fun currentTail(): String = DocumentFileName.normalizeTailNumber(
        prefs.getString(DocumentFileName.PREF_LAST_TAIL, "").orEmpty()
    )

    private fun updatePreview() {
        fileNamePreview.text = DocumentFileName.create(currentTail(), surnameInput.text.toString())
    }

    private fun label(text: String, color: Int = mutedColor) = TextView(context).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.07f
        setTextColor(color)
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun fullWidth(bottom: Int = 0) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
