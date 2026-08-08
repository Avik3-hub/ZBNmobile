package com.example.zbnreader

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 1. Находим элементы UI по их ID из activity_settings.xml
        val spinnerSystemType = findViewById<Spinner>(R.id.spinnerSystemType)
        val spinnerArinc = findViewById<Spinner>(R.id.spinnerArinc)
        val spinnerRegSpeed = findViewById<Spinner>(R.id.spinnerRegSpeed)
        val spinnerInterface = findViewById<Spinner>(R.id.spinnerInterface)
        val spinnerBaudRate = findViewById<Spinner>(R.id.spinnerBaudRate)
        val etListLimit = findViewById<EditText>(R.id.etListLimit)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)

        // 2. Читаем ранее сохраненные значения и устанавливаем их
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

            editor.putString("limit", etListLimit.text.toString())

            editor.apply()

            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
            finish() // Закрываем экран настроек и возвращаемся в главный экран
        }
    }
}
