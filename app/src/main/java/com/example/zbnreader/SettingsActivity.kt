package com.example.zbnreader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val COLOR_BG = Color.parseColor("#131314")
    private val COLOR_SURFACE = Color.parseColor("#1E1F20")
    private val COLOR_ACCENT = Color.parseColor("#A8C7FA")
    private val COLOR_ACCENT_TEXT = Color.parseColor("#041E49")
    private val COLOR_TEXT = Color.parseColor("#E3E3E3")
    private val COLOR_BORDER = Color.parseColor("#444746")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(COLOR_BG)
        }

        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)

        // 1. Поле лимита включений (дефолт: 6)
        val tvLimitLabel = TextView(this).apply {
            text = "Количество выводимых включений:"
            setTextColor(COLOR_TEXT)
            textSize = 14f
        }
        val etLimit = EditText(this).apply {
            val savedLimit = try {
                prefs.getInt("limit", 6)
            } catch (_: Exception) {
                prefs.getString("limit", "6")?.toIntOrNull() ?: 6
            }
            setText(savedLimit.toString())
            setTextColor(COLOR_TEXT)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = createRoundedDrawable(COLOR_SURFACE, 12f, COLOR_BORDER, 1)
            setPadding(20, 16, 20, 16)
        }

        // 2. Выпадающие списки (дефолт: индекс 0)
        val spinnerSysType = createSpinner(R.array.system_types, prefs.getInt("system_type", 0))
        val spinnerArinc = createSpinner(R.array.arinc_types, prefs.getInt("arinc", 0))
        val spinnerRegSpeed = createSpinner(R.array.reg_speeds, prefs.getInt("reg_speed", 0))
        val spinnerBaudRate = createSpinner(R.array.baud_rates, getBaudRateIndex(prefs.getInt("baud_rate", 921600)))

        // 3. Кнопка сохранения
        val btnSave = Button(this).apply {
            text = "СОХРАНИТЬ НАСТРОЙКИ"
            setTextColor(COLOR_ACCENT_TEXT)
            background = createRoundedDrawable(COLOR_ACCENT, 20f)
            setOnClickListener {
                val limitVal = etLimit.text.toString().toIntOrNull() ?: 6
                val baudRates = resources.getStringArray(R.array.baud_rates)
                val selectedBaud = baudRates.getOrNull(spinnerBaudRate.selectedItemPosition)?.toIntOrNull() ?: 921600

                prefs.edit().apply {
                    putInt("limit", limitVal)
                    putInt("system_type", spinnerSysType.selectedItemPosition)
                    putInt("arinc", spinnerArinc.selectedItemPosition)
                    putInt("reg_speed", spinnerRegSpeed.selectedItemPosition)
                    putInt("baud_rate", selectedBaud)
                    apply()
                }

                Toast.makeText(this@SettingsActivity, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // Добавление элементов на экран
        root.addView(tvLimitLabel)
        root.addView(etLimit)
        root.addView(createLabel("Тип системы регистрации:"))
        root.addView(spinnerSysType)
        root.addView(createLabel("Протокол ARINC:"))
        root.addView(spinnerArinc)
        root.addView(createLabel("Скорость регистрации (поз./с):"))
        root.addView(spinnerRegSpeed)
        root.addView(createLabel("Скорость обмена (Бод):"))
        root.addView(spinnerBaudRate)

        val saveParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 32, 0, 0) }
        root.addView(btnSave, saveParams)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun createLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(COLOR_TEXT)
        textSize = 14f
        setPadding(0, 24, 0, 8)
    }

    private fun createSpinner(arrayResId: Int, selectedIndex: Int): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter.createFromResource(
                this@SettingsActivity,
                arrayResId,
                android.R.layout.simple_spinner_dropdown_item
            )
            setSelection(selectedIndex)
            background = createRoundedDrawable(COLOR_SURFACE, 12f, COLOR_BORDER, 1)
        }
    }

    private fun getBaudRateIndex(baudRate: Int): Int {
        val rates = resources.getStringArray(R.array.baud_rates)
        val index = rates.indexOf(baudRate.toString())
        return if (index >= 0) index else 0
    }

    private fun createRoundedDrawable(bgColor: Int, radiusDp: Float, strokeColor: Int = 0, strokeWidthPx: Int = 0): GradientDrawable {
        val radius = radiusDp * resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = radius
            if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
        }
    }
}
