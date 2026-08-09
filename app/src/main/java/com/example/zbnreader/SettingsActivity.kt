package com.example.zbnreader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private val COLOR_BG = Color.parseColor("#131314")
    private val COLOR_SURFACE = Color.parseColor("#1E1F20")
    private val COLOR_SURFACE_CONTAINER = Color.parseColor("#28292A")
    private val COLOR_ACCENT = Color.parseColor("#A8C7FA")
    private val COLOR_ACCENT_TEXT = Color.parseColor("#041E49")
    private val COLOR_TEXT = Color.parseColor("#E3E3E3")
    private val COLOR_TEXT_MUTED = Color.parseColor("#C4C7C5")
    private val COLOR_BORDER = Color.parseColor("#444746")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)

        val root = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            setFillViewport(true)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // Заголовок экрана
        val tvTitle = TextView(this).apply {
            text = "Настройки приложения"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, 24)
        }
        container.addView(tvTitle)

        // 1. Карточка: Скорость передачи (Baud Rate)
        val cardBaud = createCardLayout()
        val lblBaud = createLabel("Скорость передачи (Baud Rate)")
        val spinnerBaud = createSpinner(arrayOf("9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600"))
        
        val currentBaud = prefs.getInt("baud_rate", 115200)
        val baudValues = arrayOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
        spinnerBaud.setSelection(baudValues.indexOf(currentBaud).coerceAtLeast(4))

        cardBaud.addView(lblBaud)
        cardBaud.addView(spinnerBaud)
        container.addView(cardBaud)

        // 2. Карточка: Лимит включений
        val cardLimit = createCardLayout()
        val lblLimit = createLabel("Лимит сканируемых включений")
        val inputLimit = EditText(this).apply {
            textSize = 14f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_TEXT_MUTED)
            setText(prefs.getInt("limit", 10).toString())
            background = createRoundedDrawable(COLOR_SURFACE_CONTAINER, 12f, COLOR_BORDER, 1)
            setPadding(24, 24, 24, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        cardLimit.addView(lblLimit)
        cardLimit.addView(inputLimit)
        container.addView(cardLimit)

        // 3. Карточка: Тип системы регистрации
        val cardSys = createCardLayout()
        val lblSys = createLabel("Тип системы регистрации")
        val spinnerSys = createSpinner(resources.getStringArray(R.array.system_types))
        spinnerSys.setSelection(prefs.getInt("system_type", 0))
        cardSys.addView(lblSys)
        cardSys.addView(spinnerSys)
        container.addView(cardSys)

        // 4. Карточка: Протокол ARINC
        val cardArinc = createCardLayout()
        val lblArinc = createLabel("Протокол ARINC")
        val spinnerArinc = createSpinner(resources.getStringArray(R.array.arinc_types))
        spinnerArinc.setSelection(prefs.getInt("arinc", 0))
        cardArinc.addView(lblArinc)
        cardArinc.addView(spinnerArinc)
        container.addView(cardArinc)

        // 5. Карточка: Скорость регистрации
        val cardSpeed = createCardLayout()
        val lblSpeed = createLabel("Скорость регистрации")
        val spinnerSpeed = createSpinner(resources.getStringArray(R.array.reg_speeds))
        spinnerSpeed.setSelection(prefs.getInt("reg_speed", 0))
        cardSpeed.addView(lblSpeed)
        cardSpeed.addView(spinnerSpeed)
        container.addView(cardSpeed)

        // 6. Карточка: Экспорт лога (Возвращенное меню)
        val cardLog = createCardLayout()
        val lblLog = createLabel("Диагностика и логи")
        val btnExportLog = Button(this).apply {
            text = "ВЫГРУЗИТЬ ЛОГ РАБОТЫ"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            background = createRoundedDrawable(COLOR_SURFACE_CONTAINER, 16f, COLOR_BORDER, 1)
            setPadding(16, 20, 16, 20)
            setOnClickListener {
                exportAppLog()
            }
        }
        cardLog.addView(lblLog)
        cardLog.addView(btnExportLog)
        container.addView(cardLog)

        // Кнопка сохранения
        val btnSave = Button(this).apply {
            text = "СОХРАНИТЬ НАСТРОЙКИ"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_ACCENT_TEXT)
            background = createRoundedDrawable(COLOR_ACCENT, 24f)
            setPadding(16, 26, 16, 26)
            setOnClickListener {
                val selectedBaud = baudValues[spinnerBaud.selectedItemPosition]
                val limitVal = inputLimit.text.toString().toIntOrNull() ?: 10

                prefs.edit().apply {
                    putInt("baud_rate", selectedBaud)
                    putInt("limit", limitVal)
                    putInt("system_type", spinnerSys.selectedItemPosition)
                    putInt("arinc", spinnerArinc.selectedItemPosition)
                    putInt("reg_speed", spinnerSpeed.selectedItemPosition)
                    apply()
                }

                Toast.makeText(this@SettingsActivity, "Настройки успешно сохранены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        val saveParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 16, 0, 24) }
        btnSave.layoutParams = saveParams
        container.addView(btnSave)

        root.addView(container)
        setContentView(root)
    }

    private fun exportAppLog() {
        try {
            val rootDir = Environment.getExternalStorageDirectory()
            val mainFolder = File(rootDir, "ZBNreader")
            if (!mainFolder.exists()) {
                mainFolder.mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val logFile = File(mainFolder, "app_log_$timeStamp.txt")

            val sharedPrefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val logText = buildString {
                appendLine("=== ZBN READER APP LOG ===")
                appendLine("Дата/Время: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Скорость передачи (Baud Rate): ${sharedPrefs.getInt("baud_rate", 115200)}")
                appendLine("Лимит сканируемых включений: ${sharedPrefs.getInt("limit", 10)}")
                appendLine("Тип системы регистрации: ${sharedPrefs.getInt("system_type", 0)}")
                appendLine("Протокол ARINC: ${sharedPrefs.getInt("arinc", 0)}")
                appendLine("Скорость регистрации: ${sharedPrefs.getInt("reg_speed", 0)}")
            }

            logFile.writeText(logText)
            Toast.makeText(this, "Лог успешно сохранен в папку ZBNreader", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения лога: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createCardLayout(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(COLOR_SURFACE, 20f)
            setPadding(24, 24, 24, 24)
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) }
        card.layoutParams = params
        return card
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_ACCENT)
            setPadding(0, 0, 0, 12)
        }
    }

    private fun createSpinner(items: Array<String>): Spinner {
        val spinner = Spinner(this).apply {
            background = createRoundedDrawable(COLOR_SURFACE_CONTAINER, 12f, COLOR_BORDER, 1)
            setPadding(16, 16, 16, 16)
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.adapter = adapter
        return spinner
    }

    private fun createRoundedDrawable(backgroundColor: Int, radiusDp: Float, strokeColor: Int = 0, strokeWidthPx: Int = 0): GradientDrawable {
        val radius = radiusDp * resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            cornerRadius = radius
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }
}
