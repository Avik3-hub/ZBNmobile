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

        // 1. Создаем главный контейнер
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // 2. Кнопка "Настройки"
        val btnSettings = Button(this).apply {
            text = "Настройки"
            setOnClickListener {
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startActivity(intent)
            }
        }
        root.addView(btnSettings)

        // 3. Текстовый статус
        tvStatus = TextView(this).apply {
            text = "Статус: Подключите ЗБН и нажмите Считать"
            textSize = 16f
            setPadding(0, 0, 0, 20)
        }
        root.addView(tvStatus)

        // 4. Кнопка "Считать ЗБН"
        btnStart = Button(this).apply {
            text = "СЧИТАТЬ ЗБН"
            textSize = 18f
            setOnClickListener { startReading() }
        }
        root.addView(btnStart)

        // 5. Полоса прогресса
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            setPadding(0, 20, 0, 20)
        }
        root.addView(progressBar)

        // 6. ИНИЦИАЛИЗАЦИЯ tvLog (окно для вывода логов с прокруткой)
        val scrollView = ScrollView(this)
        tvLog = TextView(this).apply {
            textSize = 12f
            setPadding(0, 20, 0, 0)
        }
        scrollView.addView(tvLog)
        root.addView(scrollView)

        // 7. Отображаем весь макет
        setContentView(root)
    }


    private fun log(message: String) {
        runOnUiThread { tvLog.append("$message\n") }
    }

    private fun startReading() {
    btnStart.isEnabled = false
    progressBar.visibility = View.VISIBLE
    tvLog.text = ""
    tvStatus.text = "Статус: Чтение настроек и подключение..."

    // 1. Считываем ВСЕ сохраненные настройки из памяти
    val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
    val currentBaudRate = prefs.getInt("baud_rate", 115200)
    val limitStr = prefs.getString("limit", "10") ?: "10"
    val sessionLimit = limitStr.toIntOrNull() ?: 10

    val sysTypeIndex = prefs.getInt("system_type", 0)
    val arincIndex = prefs.getInt("arinc", 0)
    val regSpeedIndex = prefs.getInt("reg_speed", 0)

    log("Загружены настройки:")
    log(" • Скорость (Baud Rate): $currentBaudRate")
    log(" • Лимит включений: $sessionLimit")

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
            // Устанавливаем выбранную скорость обмена
            port.setParameters(currentBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = false
            port.rts = false

            log("Порт открыт: $currentBaudRate 8N1")

            // Рукопожатие: ENQ (0x05)
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

            // 2. Запрос оглавления ЗБН (списка включений)
            log("Запрос каталога включений (лимит: $sessionLimit)...")
            
            // Вызываем функцию чтения оглавления
            val flightList = readCatalog(port, sessionLimit)

            if (flightList.isEmpty()) {
                log("Включения не найдены или выполняется дамп всей памяти...")
                // Если оглавления нет, выполняем полный дамп памяти по команде 'M'
                downloadFullDump(port)
            } else {
                log("Найдено включений: ${flightList.size}")
                flightList.forEachIndexed { index, flight ->
                    log(" Рейс #${index + 1}: $flight")
                }
                updateStatus("Статус: Найдено ${flightList.size} включений")
            }

        } catch (e: Exception) {
            log("Ошибка: ${e.message}")
            updateStatus("Статус: Сбой передачи")
        } finally {
            try { port.close() } catch (_: Exception) {}
            resetUi()
        }
    }
}
// Функция считывания оглавления ЗБН и применения лимита
private fun readCatalog(port: UsbSerialPort, limit: Int): List<String> {
    val flights = mutableListOf<String>()

    // Отправляем команду запроса оглавления/паспорта (команда 'I' или 'C' в зависимости от протокола ЗБН)
    // В данном примере запрашиваем заголовки кадров
    log("Отправка команды запроса оглавления...")
    port.write(byteArrayOf('I'.code.toByte()), 1000)

    val buffer = ByteArray(256)
    val count = port.read(buffer, 1500)

    if (count > 0) {
        // Симулируем разбор полученных записей о полетах из оглавления
        // Здесь считываются метки времени включения питания ЗБН
        val totalFoundInMemory = count / 16 // Допустим, 1 запись = 16 байт
        
        for (i in 0 until totalFoundInMemory) {
            flights.add("Включение №${i + 1}")
        }

        // Обрезаем список по заданному лимиту
        return flights.takeLast(limit)
    }

    return emptyList()
}

// Функция полного дампа памяти (если считываем весь накопитель)
private fun downloadFullDump(port: UsbSerialPort) {
    log("Отправка команды 'M' (0x4D) для считывания всей памяти...")
    port.write(byteArrayOf(0x4D.toByte()), 1000)

    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val fileName = "ZBN_DUMP_$timeStamp.bin"
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val outputFile = File(downloadsDir, fileName)
    val fos = FileOutputStream(outputFile)

    val buffer = ByteArray(512)
    var totalBytes = 0
    var noDataCounter = 0

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
                log("Прием завершен.")
                break
            }
        }
    }

    fos.flush()
    fos.close()
    log("Файл сохранен: Загрузки/$fileName")
    updateStatus("Статус: Готово ($totalBytes Б)")
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
