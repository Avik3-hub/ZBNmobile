package com.example.zbnreader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class DocumentScanPage(context: Context) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    private val tailValue = TextView(context)
    private val surnameInput = EditText(context)
    private val fileNamePreview = TextView(context)
    private val megapixels = intArrayOf(1, 2, 3, 5, 8, 12)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        setBackgroundColor(Color.parseColor("#131314"))

        addView(TextView(context).apply {
            text = "Сканер БУР-1"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E3E3E3"))
        }, fullWidth(bottom = 24))

        addLabel("Номер борта")
        tailValue.textSize = 20f
        tailValue.setTextColor(Color.parseColor("#A8C7FA"))
        tailValue.setPadding(dp(12), dp(10), dp(12), dp(14))
        addView(tailValue, fullWidth(bottom = 14))

        addLabel("Фамилия для имени файла")
        surnameInput.setText(prefs.getString(DocumentFileName.PREF_SURNAME, "НАГИБИН"))
        surnameInput.setTextColor(Color.parseColor("#E3E3E3"))
        surnameInput.setHintTextColor(Color.parseColor("#757775"))
        surnameInput.hint = "ФАМИЛИЯ"
        surnameInput.setSingleLine(true)
        addView(surnameInput, fullWidth(bottom = 16))

        addLabel("Разрешение снимка")
        val resolutionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                megapixels.map { "$it Мп" }
            )
            val saved = prefs.getInt(DocumentFileName.PREF_MEGAPIXELS, 5)
            setSelection(megapixels.indexOf(saved).coerceAtLeast(0))
        }
        addView(resolutionSpinner, fullWidth(bottom = 20))

        addLabel("Имя JPEG")
        fileNamePreview.textSize = 14f
        fileNamePreview.setTextColor(Color.parseColor("#A8C7FA"))
        fileNamePreview.setPadding(dp(12), dp(12), dp(12), dp(20))
        addView(fileNamePreview, fullWidth(bottom = 18))

        val scanButton = Button(context).apply {
            text = "СФОТОГРАФИРОВАТЬ ДОКУМЕНТ"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
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
        addView(scanButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))

        surnameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh()
    }

    fun refresh() {
        val tail = currentTail()
        tailValue.text = if (tail.isEmpty()) "Не определён" else tail
        updatePreview()
    }

    private fun currentTail(): String = DocumentFileName.normalizeTailNumber(
        prefs.getString(DocumentFileName.PREF_LAST_TAIL, "").orEmpty()
    )

    private fun updatePreview() {
        fileNamePreview.text = DocumentFileName.create(currentTail(), surnameInput.text.toString())
    }

    private fun addLabel(text: String) {
        addView(TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
        }, fullWidth(bottom = 3))
    }

    private fun fullWidth(bottom: Int = 0) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
