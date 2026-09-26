package com.example.zbnreader

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : AppCompatActivity() {
    private lateinit var palette: ZbnPalette
    private val COLOR_BG get() = palette.background
    private val COLOR_SURFACE get() = palette.surface
    private val COLOR_ACCENT get() = palette.accent
    private val COLOR_ACCENT_TEXT get() = palette.accentText
    private val COLOR_TEXT get() = palette.text
    private val COLOR_BORDER get() = palette.border

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = ZbnTheme.palette(this)
        ZbnTheme.applySystemBars(this, palette)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(COLOR_BG)
        }

        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)

        // 0. ВЕРХНЯЯ ПАНЕЛЬ С КНОПКОЙ НАЗАД
        val headerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }

        val btnBack = TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 32, 0)
            setOnClickListener { finish() }
        }

        val tvTitle = TextView(this).apply {
            text = "Настройки приложения"
            textSize = 18f
            setTextColor(COLOR_TEXT)
        }

        headerPanel.addView(btnBack)
        headerPanel.addView(tvTitle)
        root.addView(headerPanel)

        // 1. Поле лимита включений (дефолт: 6)
        val etLimit = EditText(this).apply {
            val savedLimit = try {
                prefs.getInt("limit", 6)
            } catch (_: Exception) {
                prefs.getString("limit", "6")?.toIntOrNull() ?: 6
            }
            setText(savedLimit.toString())
            setTextColor(COLOR_TEXT)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = createRoundedDrawable(COLOR_SURFACE, 12f, COLOR_BORDER, 1)
            setPadding(24, 20, 24, 20)
        }

        // 2. Выпадающие списки с кастомной стилизацией
        val spinnerSysType = createCustomSpinner(R.array.system_types, prefs.getInt("system_type", 0))
        val spinnerArinc = createCustomSpinner(R.array.arinc_types, prefs.getInt("arinc", 0))
        val spinnerRegSpeed = createCustomSpinner(R.array.reg_speeds, prefs.getInt("reg_speed", 0))
        val spinnerBaudRate = createCustomSpinner(
            R.array.baud_rates,
            getBaudRateIndex(prefs.getInt("baud_rate", DEFAULT_BAUD_RATE))
        )

        val switchConnectionScene = SwitchCompat(this).apply {
            text = "Анимация обмена с ЗБН"
            textSize = 14f
            setTextColor(COLOR_TEXT)
            isChecked = prefs.getBoolean("zbn_connection_scene_enabled", true)
            setPadding(0, 20, 0, 12)
        }

        val switchLightTheme = SwitchCompat(this).apply {
            text = "Светлая тема оформления"
            textSize = 14f
            setTextColor(COLOR_TEXT)
            isChecked = prefs.getBoolean(ZbnTheme.PREF_LIGHT_THEME, false)
            setPadding(0, 20, 0, 12)
        }

        val btnDemoConnection = Button(this).apply {
            text = "ПОКАЗАТЬ АНИМАЦИЮ СВЯЗИ"
            setTextColor(COLOR_TEXT)
            textSize = 13f
            background = createRoundedDrawable(COLOR_SURFACE, 12f, COLOR_BORDER, 1)
            setPadding(16, 18, 16, 18)
            setOnClickListener {
                prefs.edit()
                    .putBoolean("zbn_connection_scene_enabled", true)
                    .putBoolean("zbn_connection_scene_demo_pending", true)
                    .apply()
                Toast.makeText(this@SettingsActivity, "Запускаю демонстрацию", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // 3. Кнопка сохранения
        val btnSave = Button(this).apply {
            text = "СОХРАНИТЬ НАСТРОЙКИ"
            setTextColor(COLOR_ACCENT_TEXT)
            textSize = 14f
            background = createRoundedDrawable(COLOR_ACCENT, 20f)
            setPadding(16, 24, 16, 24)
            setOnClickListener {
                val limitVal = etLimit.text.toString().toIntOrNull() ?: 6
                val baudRates = resources.getStringArray(R.array.baud_rates)
                val selectedBaud = baudRates
                    .getOrNull(spinnerBaudRate.selectedItemPosition)
                    ?.toIntOrNull()
                    ?: DEFAULT_BAUD_RATE

                prefs.edit().apply {
                    putInt("limit", limitVal)
                    putInt("system_type", spinnerSysType.selectedItemPosition)
                    putInt("arinc", spinnerArinc.selectedItemPosition)
                    putInt("reg_speed", spinnerRegSpeed.selectedItemPosition)
                    putInt("baud_rate", selectedBaud)
                    putBoolean("zbn_connection_scene_enabled", switchConnectionScene.isChecked)
                    putBoolean(ZbnTheme.PREF_LIGHT_THEME, switchLightTheme.isChecked)
                    apply()
                }

                Toast.makeText(this@SettingsActivity, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // 4. Кнопка сохранения логов
        val btnSaveLog = Button(this).apply {
            text = "СОХРАНИТЬ ЛОГ ОШИБОК"
            setTextColor(COLOR_TEXT)
            textSize = 14f
            background = createRoundedDrawable(COLOR_SURFACE, 20f, COLOR_BORDER, 1)
            setPadding(16, 24, 16, 24)
            setOnClickListener {
                saveLogFile()
            }
        }

        // Добавление элементов на экран
        root.addView(createLabel("Количество выводимых включений:"))
        root.addView(etLimit)
        root.addView(createLabel("Тип системы регистрации:"))
        root.addView(spinnerSysType)
        root.addView(createLabel("Протокол ARINC:"))
        root.addView(spinnerArinc)
        root.addView(createLabel("Скорость регистрации (поз./с):"))
        root.addView(spinnerRegSpeed)
        root.addView(createLabel("Скорость обмена RS-422 (Бод):"))
        root.addView(spinnerBaudRate)
        root.addView(createLabel("Оформление:"))
        root.addView(switchLightTheme)
        root.addView(switchConnectionScene)
        root.addView(btnDemoConnection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 0) })

        val saveParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 40, 0, 0) }
        root.addView(btnSave, saveParams)

        val saveLogParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 20, 0, 0) }
        root.addView(btnSaveLog, saveLogParams)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            addView(root)
        })
    }

    private fun saveLogFile() {
    try {
        // Получаем исходный лог файл из папки приложения
        val logDir = File(getExternalFilesDir(null), "ZBNreader")
        val sourceLogFile = File(logDir, "zbn_app_log.txt")

        if (!sourceLogFile.exists() || sourceLogFile.length() == 0L) {
            Toast.makeText(this, "Файл лога пуст или еще не создан", Toast.LENGTH_SHORT).show()
            return
        }

        // Жестко указываем корень телефона (благо разрешение MANAGE_EXTERNAL_STORAGE получено)
        val rootDir = Environment.getExternalStorageDirectory()
        val zbsFolder = File(rootDir, "ZBNreader")
        if (!zbsFolder.exists()) {
            zbsFolder.mkdirs()
        }

        // Создаем имя файла с меткой времени
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val destLogFile = File(zbsFolder, "zbn_app_log_$timeStamp.txt")

        // Копируем содержимое файла
        sourceLogFile.copyTo(destLogFile, overwrite = true)

        Toast.makeText(
            this,
            "Лог успешно сохранен в ZBNreader/${destLogFile.name}",
            Toast.LENGTH_LONG
        ).show()

        android.util.Log.i("SettingsActivity", "Лог сохранен в: ${destLogFile.absolutePath}")
    } catch (e: Exception) {
        Toast.makeText(this, "Ошибка при сохранении лога: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        android.util.Log.e("SettingsActivity", "Ошибка сохранения лога", e)
    }
}

    private fun createLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(COLOR_TEXT)
        textSize = 13f
        setPadding(0, 20, 0, 8)
    }

    private fun createCustomSpinner(arrayResId: Int, selectedIndex: Int): Spinner {
        val items = resources.getStringArray(arrayResId)
        
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(COLOR_TEXT)
                v.textSize = 14f
                v.setPadding(24, 20, 24, 20)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(COLOR_TEXT)
                v.setBackgroundColor(COLOR_SURFACE)
                v.textSize = 14f
                v.setPadding(24, 20, 24, 20)
                return v
            }
        }

        return Spinner(this).apply {
            this.adapter = adapter
            setSelection(if (selectedIndex >= 0 && selectedIndex < items.size) selectedIndex else 0)
            background = createRoundedDrawable(COLOR_SURFACE, 12f, COLOR_BORDER, 1)
        }
    }

    private fun getBaudRateIndex(baudRate: Int): Int {
        val rates = resources.getStringArray(R.array.baud_rates)
        val index = rates.indexOf(baudRate.toString())
        if (index >= 0) return index
        
        val defaultIndex = rates.indexOf(DEFAULT_BAUD_RATE.toString())
        return if (defaultIndex >= 0) defaultIndex else 0
    }

    companion object {
        private const val DEFAULT_BAUD_RATE = 115200
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
