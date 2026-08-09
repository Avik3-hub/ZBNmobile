package com.example.zbnreader

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Включаем стрелку "Назад" в верхней панели (ActionBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки приложения"

        // 1. Находим элементы UI по их ID
        val spinnerSystemType = findViewById<Spinner>(R.id.spinnerSystemType)
        val spinnerArinc = findViewById<Spinner>(R.id.spinnerArinc)
        val spinnerRegSpeed = findViewById<Spinner>(R.id.spinnerRegSpeed)
        val spinnerInterface = findViewById<Spinner>(R.id.spinnerInterface)
        val spinnerBaudRate = findViewById<Spinner>(R.id.spinnerBaudRate)
        val etListLimit = findViewById<EditText>(R.id.etListLimit)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnShareLog = findViewById<Button>(R.id.btnShareLog) // Находим кнопку лога

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)

        // 2. Читаем ранее сохраненные значения
        spinnerSystemType.setSelection(prefs.getInt("system_type", 0))
        spinnerArinc.setSelection(prefs.getInt("arinc", 0))
        spinnerRegSpeed.setSelection(prefs.getInt("reg_speed", 0))
        spinnerInterface.setSelection(prefs.getInt("interface", 0))

        // Восстанавливаем сохраненную скорость обмена (Baud Rate)
        val savedSpeed = prefs.getInt("baud_rate", 115200).toString()
        val baudRates = resources.getStringArray(R.array.baud_rates)
        val speedIndex = baudRates.indexOf(savedSpeed)
        if (speedIndex >= 0) {
            spinnerBaudRate.setSelection(speedIndex)
        }

        // Восстанавливаем число вывода в списке
        etListLimit.setText(prefs.getString("limit", "10"))

        // 3. Сохранение при нажатии на кнопку
        btnSave.setOnClickListener {
            val editor = prefs.edit()

            editor.putInt("system_type", spinnerSystemType.selectedItemPosition)
            editor.putInt("arinc", spinnerArinc.selectedItemPosition)
            editor.putInt("reg_speed", spinnerRegSpeed.selectedItemPosition)
            editor.putInt("interface", spinnerInterface.selectedItemPosition)

            // Сохраняем выбранную скорость как число (например, 921600 или 115200)
            val selectedSpeedStr = spinnerBaudRate.selectedItem.toString()
            val baudRateInt = selectedSpeedStr.toIntOrNull() ?: 115200
            editor.putInt("baud_rate", baudRateInt)

            // Проверка поля ввода лимита на пустоту
            val limitText = etListLimit.text.toString().trim()
            val safeLimit = if (limitText.isEmpty() || limitText.toIntOrNull() == 0) "10" else limitText
            editor.putString("limit", safeLimit)

            editor.apply()

            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
            finish()
        }

        // 4. Отправка лога при нажатии на кнопку
        btnShareLog.setOnClickListener {
            shareLogFile()
        }
    }

    private fun shareLogFile() {
        // Укажите точное имя файла, в который приложение записывает логи
        val logFile = File(filesDir, "zbn_app_log.txt")

        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, "Файл лога пуст или еще не создан", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            logFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, "Отправить лог работы ЗБН"))
    }

    // Обработка нажатия на стрелку "Назад" в верхней панели
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
