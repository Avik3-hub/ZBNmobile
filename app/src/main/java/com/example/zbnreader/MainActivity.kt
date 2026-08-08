package com.example.zbnreader

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
val btnSettings = findViewById<Button>(R.id.btnSettings)
btnSettings.setOnClickListener {
    val intent = Intent(this, SettingsActivity::class.java)
    startActivity(intent)
}

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        tvStatus = TextView(this).apply {
            text = "Статус: Подключите ЗБН и нажмите Считать"
            textSize = 16f
            setPadding(0, 0, 0, 20)
        }
        root.addView(tvStatus)

        btnStart = Button(this).apply {
            text = "СЧИТАТЬ ЗБН"
            textSize = 18f
            setOnClickListener { startReading() }
        }
        root.addView(btnStart)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            setPadding(0, 20, 0, 20)
        }
        root.addView(progressBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        tvLog = TextView(this).apply {
            textSize = 12f
            setPadding(0, 20, 0, 0)
        }
        scrollView.addView(tvLog)
        root.addView(scrollView)

        setContentView(root)
    }

    private fun log(message: String) {
        runOnUiThread { tvLog.append("$message\n") }
    }

    private fun startReading() {
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvLog.text = ""
        tvStatus.text = "Статус: Чтение..."

        lifecycleScope.launch(Dispatchers.IO) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

            if (drivers.isEmpty()) {
                log("Ошибка: USB-RS422 конвертер не обнаружен!")
                updateStatus("Статус: Ошибка (Нет адаптера)")
                resetUi()
                return@launch
            }

            val driver = drivers[0]
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                log("Ошибка: Нет разрешения на использование USB!")
                updateStatus("Статус: Ошибка доступа к USB")
                resetUi()
                return@launch
            }

            val port = driver.ports[0]
            try {
                port.open(connection)
                val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
val currentBaudRate = prefs.getInt("baud_rate", 115200)
port.setParameters(currentBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                  port.dtr = false
                port.rts = false

                log("Порт открыт: 115200 8N1")

                // 1. Рукопожатие: ENQ (0x05)
                log("Отправка ENQ (0x05)...")
                port.write(byteArrayOf(0x05), 1000)

                val ackBuf = ByteArray(1)
                val readAck = port.read(ackBuf, 1000)
                if (readAck == 0 || ackBuf[0] != 0x06.toByte()) {
                    log("Ошибка: Ответ от ЗБН не получен (ожидался ACK 0x06)")
                    updateStatus("Статус: Сбой рукопожатия")
                    resetUi()
                    return@launch
                }

                log("Получен ответ ACK (0x06)!")

                // 2. Команда 'M' (0x4D)
                log("Отправка команды 'M' (0x4D)...")
                port.write(byteArrayOf(0x4D.toByte()), 1000)

                // 3. Выгрузка в папку Downloads
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "ZBN_DUMP_$timeStamp.bin"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = File(downloadsDir, fileName)
                val fos = FileOutputStream(outputFile)

                val buffer = ByteArray(512)
                var totalBytes = 0
                var noDataCounter = 0

                log("Прием данных...")
                while (true) {
                    val count = port.read(buffer, 1000)
                    if (count > 0) {
                        fos.write(buffer, 0, count)
                        totalBytes += count
                        noDataCounter = 0
                        log("Принято: $totalBytes байт")
                    } else {
                        noDataCounter++
                        if (noDataCounter >= 3) {
                            log("Прием завершен (таймаут).")
                            break
                        }
                    }
                }

                fos.flush()
                fos.close()

                log("УСПЕХ! Принято байт: $totalBytes")
                log("Файл сохранен: Загрузки/$fileName")
                updateStatus("Статус: Готово ($totalBytes Б)")

            } catch (e: Exception) {
                log("Ошибка исключения: ${e.message}")
                updateStatus("Статус: Сбой передачи")
            } finally {
                try { port.close() } catch (_: Exception) {}
                resetUi()
            }
        }
    }

    private fun updateStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
    }

    private fun resetUi() {
        runOnUiThread {
            btnStart.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }
}
