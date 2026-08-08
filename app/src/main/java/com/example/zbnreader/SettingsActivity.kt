package com.example.zbnmobile // Замените на ваш package из MainActivity

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val spinnerBaudRate = findViewById<Spinner>(R.id.spinnerBaudRate)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        // Возможные скорости для RS-422
        const val speeds = arrayOf("9600", "19200", "38400", "57600", "115200")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speeds)
        spinnerBaudRate.adapter = adapter

        // Читаем уже сохраненное значение
        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val savedSpeed = prefs.getInt("baud_rate", 115200)
        val spinnerPosition = speeds.indexOf(savedSpeed.toString())
        if (spinnerPosition >= 0) {
            spinnerBaudRate.setSelection(spinnerPosition)
        }

        // Сохранение по нажатию кнопки
        btnSave.setOnClickListener {
            val selectedSpeed = spinnerBaudRate.selectedItem.toString().toInt()
            prefs.edit().putInt("baud_rate", selectedSpeed).apply()
            
            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
            finish() // Закрываем экран настроек
        }
    }
}

