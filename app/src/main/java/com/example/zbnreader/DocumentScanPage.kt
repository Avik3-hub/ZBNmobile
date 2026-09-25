package com.example.zbnreader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
    private val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    private val tailValue = TextView(context)
    private val surnameInput = EditText(context)
    private val fileNameInput = EditText(context)
    private var updatingFileName = false
    private var fileNameEdited = false
    private val megapixels = intArrayOf(1, 2, 3, 5, 8, 12)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(7), dp(16), dp(6))
        setBackgroundColor(backgroundColor)

        val header = FrameLayout(context)
        val headerText = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = "ZBN Mobile"
                textSize = 18f
                typeface = resources.getFont(R.font.zbn_sans_bold)
                setTextColor(textColor)
            }, fullWidth(bottom = 1))
            addView(TextView(context).apply {
                text = "ДОКУМЕНТЫ ЭКИПАЖА"
                textSize = 9.5f
                letterSpacing = 0.09f
                setTextColor(amberColor)
            }, fullWidth(bottom = 4))
            addView(TextView(context).apply {
                text = "Паспорт БУР-1"
                textSize = 25f
                typeface = resources.getFont(R.font.zbn_sans_bold)
                setTextColor(textColor)
            }, fullWidth(bottom = 3))
            addView(TextView(context).apply {
                text = "Съёмка и подготовка документа"
                textSize = 11.5f
                setTextColor(mutedColor)
            }, fullWidth())
        }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.zbn_blueprint_mi171_front)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Color.parseColor("#4F8BC4"), PorterDuff.Mode.SRC_ATOP)
            alpha = 0.92f
            contentDescription = null
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(198), dp(116), Gravity.END or Gravity.TOP).apply {
            topMargin = dp(-4)
            marginEnd = dp(-8)
        })
        header.addView(headerText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(116)).apply {
            bottomMargin = dp(7)
        })

        val aircraftCard = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(surfaceColor, 9f)
        }
        aircraftCard.addView(label("ПОСЛЕДНИЙ БОРТ", amberColor))
        tailValue.apply {
            textSize = 20f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            setTextColor(accentColor)
            setPadding(0, dp(5), 0, 0)
        }
        aircraftCard.addView(tailValue, fullWidth())
        addView(aircraftCard, fullWidth(bottom = 8))

        val settingsCard = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(11))
            background = rounded(surfaceColor, 9f, borderColor)
        }

        settingsCard.addView(label("НАСТРОЙКИ ИМЕНИ ФАЙЛА", amberColor, 12f), fullWidth(bottom = 9))
        settingsCard.addView(label("ФАМИЛИЯ ДЛЯ ИМЕНИ ФАЙЛА"), fullWidth(bottom = 4))
        surnameInput.apply {
            setText(prefs.getString(DocumentFileName.PREF_SURNAME, "НАГИБИН"))
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            hint = "ФАМИЛИЯ"
            setSingleLine(true)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(fieldColor, 7f, borderColor)
        }
        settingsCard.addView(surnameInput, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(10)
        })

        settingsCard.addView(label("РАЗРЕШЕНИЕ СНИМКА"), fullWidth(bottom = 4))
        val resolutionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                megapixels.map { "$it Мп" }
            )
            val saved = prefs.getInt(DocumentFileName.PREF_MEGAPIXELS, 5)
            setSelection(megapixels.indexOf(saved).coerceAtLeast(0))
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(fieldColor, 7f, borderColor)
        }
        settingsCard.addView(resolutionSpinner, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply {
            bottomMargin = dp(10)
        })

        settingsCard.addView(label("БУДЕТ СОХРАНЕНО КАК"), fullWidth(bottom = 4))
        fileNameInput.apply {
            textSize = 13f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            setTextColor(accentColor)
            setSingleLine(true)
            setSelectAllOnFocus(false)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(fieldColor, 7f, borderColor)
        }
        settingsCard.addView(fileNameInput, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(settingsCard, fullWidth(bottom = 8))

        val scanButton = Button(context).apply {
            text = "СФОТОГРАФИРОВАТЬ ДОКУМЕНТ"
            textSize = 13f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            setTextColor(accentTextColor)
            minHeight = 0
            minimumHeight = 0
            background = rounded(accentColor, 7f)
            setOnClickListener {
                val surname = DocumentFileName.normalizeSurname(surnameInput.text.toString())
                val mp = megapixels[resolutionSpinner.selectedItemPosition]
                val fallbackName = DocumentFileName.create(currentTail(), surname)
                val customName = DocumentFileName.normalizeCustomFileName(
                    fileNameInput.text.toString(), fallbackName
                )
                updatingFileName = true
                fileNameInput.setText(customName)
                fileNameInput.setSelection(customName.length)
                updatingFileName = false
                prefs.edit()
                    .putString(DocumentFileName.PREF_SURNAME, surname)
                    .putInt(DocumentFileName.PREF_MEGAPIXELS, mp)
                    .apply()
                context.startActivity(Intent(context, DocumentScanActivity::class.java).apply {
                    putExtra(DocumentScanActivity.EXTRA_TAIL, currentTail())
                    putExtra(DocumentScanActivity.EXTRA_SURNAME, surname)
                    putExtra(DocumentScanActivity.EXTRA_MEGAPIXELS, mp)
                    putExtra(DocumentScanActivity.EXTRA_FILE_NAME, customName)
                })
            }
        }
        addView(scanButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))

        addView(ImageView(context).apply {
            setImageResource(R.drawable.zbn_helipad_night)
            scaleType = ImageView.ScaleType.FIT_END
            contentDescription = null
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(3)
        })

        surnameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        fileNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!updatingFileName) fileNameEdited = true
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh()
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
        if (fileNameEdited) return
        val value = DocumentFileName.create(currentTail(), surnameInput.text.toString())
        updatingFileName = true
        fileNameInput.setText(value)
        fileNameInput.setSelection(value.length)
        updatingFileName = false
    }

    private fun label(text: String, color: Int = mutedColor, size: Float = 9f) = TextView(context).apply {
        this.text = text
        textSize = size
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
