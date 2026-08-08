package com.example.zbnreader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.*
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
    private lateinit var btnCopySelected: Button
    private lateinit var btnFullDump: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tableLayout: TableLayout

    private val flightList = mutableListOf<FlightRecord>()
    private var selectedRecord: FlightRecord? = null
    private var selectedRow: TableRow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Главный контейнер
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // 1. ВЕРХНЯЯ ПАНЕЛЬ: Статус и Настройки
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }

        tvStatus = TextView(this).apply {
            text = "Статус: Подключите ЗБН и нажмите Считать"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSettings = Button(this).apply {
            text = "Настройки"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        topPanel.addView(tvStatus)
        topPanel.addView(btnSettings)
        root.addView(topPanel)

        // 2. КНОПКА СТАРТА РУКОПОЖАТИЯ И ЗАПРОСА ОГЛАВЛЕНИЯ
        btnStart = Button(this).apply {
            text = "СЧИТАТЬ ОГЛАВЛЕНИЕ ЗБН"
            textSize = 16f
            setOnClickListener { startReading() }
        }
        root.addView(btnStart)

        // 3. ТАБЛИЦА СПИСКА ВКЛЮЧЕНИЙ
        val scrollTable = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.WHITE)
            setPadding(0, 10, 0, 10)
        }

        val verticalScroll = ScrollView(this)
        tableLayout = TableLayout(this).apply {
            isStretchAllColumns = false
        }

        verticalScroll.addView(tableLayout)
        scrollTable.addView(verticalScroll)
        root.addView(scrollTable)

        // 4. ПОЛОСА ПРОГРЕССА
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progressBar)

        // 5. ПАНЕЛЬ ДЕЙСТВИЙ (КОПИРОВАНИЕ / ДАМП)
        val actionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }

        btnCopySelected = Button(this).apply {
            text = "Копировать включение"
            isEnabled = false
            setOnClickListener { copySelectedFlight() }
        }

        btnFullDump = Button(this).apply {
            text = "Полный дамп ('M')"
            setOnClickListener { executeFullDumpCommand() }
        }

        actionPanel.addView(btnCopySelected)
        actionPanel.addView(btnFullDump)
        root.addView(actionPanel)

        // 6. ОКТНО КОНСОЛИ (ЛОГИ)
        val scrollViewLog = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220
            )
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
        tvLog = TextView(this).apply {
            textSize = 11f
            setPadding(10, 10, 10, 10)
        }
        scrollViewLog.addView(tvLog)
        root.addView(scrollViewLog)

        setContentView(root)

        // Отрисовка заглавной строки таблицы
        renderTableHeader()
    }

    private fun log(message: String) {
        runOnUiThread { tvLog.append("$message\n") }
    }

    private fun renderTableHeader() {
        tableLayout.removeAllViews()
        val headerRow = TableRow(this).apply {
            setBackgroundColor(Color.parseColor("#CCCCCC"))
            setPadding(5, 8, 5, 8)
        }

        val columns = arrayOf(" № ", " Размер ", " Дата ", " Время ", " Начало ", " Конец ", " Рейс ", " Борт ")
        for (col in columns) {
            val tv = TextView(this).apply {
                text = col
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(8, 4, 8, 4)
                gravity = Gravity.CENTER
            }
            headerRow.addView(tv)
        }
        tableLayout.addView(headerRow)
    }

    private fun updateTableUI(records: List<FlightRecord>) {
        renderTableHeader()
        selectedRecord = null
        selectedRow = null
        btnCopySelected.isEnabled = false

        records.forEach { record ->
            val row = TableRow(this).apply {
                setPadding(5, 6, 5, 6)
                setOnClickListener { selectRow(this, record) }
            }

            val fields = arrayOf(
                record.number.toString(),
                "${record.sizeBytes} Б",
                record.date,
                record.duration,
                record.startTime,
                record.endTime,
                record.flightNum,
                record.tailNum
            )

            fields.forEach { textVal ->
                val tv = TextView(this).apply {
                    text = textVal
                    textSize = 12f
                    setPadding(8, 4, 8, 4)
                    gravity = Gravity.CENTER
                }
                row.addView(tv)
            }
            tableLayout.addView(row)
        }
    }

    private fun selectRow(row: TableRow, record: FlightRecord) {
        selectedRow?.setBackgroundColor(Color.TRANSPARENT)
        selectedRow = row
        selectedRow?.setBackgroundColor(Color.parseColor("#B3E5FC"))
        selectedRecord = record
        btnCopySelected.isEnabled = true
        tvStatus.text = "Выбрано включение №${record.number}"
        log("Выбрана строка: Включение №${record.number}, Рейс: ${record.flightNum}, Размер: ${record.sizeBytes} Б")
    }

    private fun startReading() {
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvLog.text = ""
        tvStatus.text = "Статус: Подключение..."

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val currentBaudRate = prefs.getInt("baud_rate", 115200)
        val limitStr = prefs.getString("limit", "10") ?: "10"
        val sessionLimit = limitStr.toIntOrNull() ?: 10

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

                log("Запрос каталога включений (лимит: $sessionLimit)...")
                val records = readCatalog(port, sessionLimit)

                runOnUiThread {
                    flightList.clear()
                    flightList.addAll(records)
                    updateTableUI(flightList)
                }

                if (records.isEmpty()) {
                    log("Включения не найдены или требуется полный дамп...")
                    updateStatus("Статус: Оглавление пусто")
                } else {
                    log("Успешно отображено включений: ${records.size}")
                    updateStatus("Статус: Загружено ${records.size} включений")
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

    private fun readCatalog(port: UsbSerialPort, limit: Int): List<FlightRecord> {
        val flights = mutableListOf<FlightRecord>()

        log("Отправка команды запроса оглавления ('I')...")
        port.write(byteArrayOf('I'.code.toByte()), 1000)

        val buffer = ByteArray(512)
        val count = port.read(buffer, 1500)

        log("Принято байт оглавления: $count")

        if (count > 0) {
            // =========================================================================
            // ЯКОРЬ_ОГЛАВЛЕНИЕ_ЗБН: Разбор байтового буфера заголовка
            // Пока временно формируем список записей для проверки UI таблицы:
            // =========================================================================
            val totalFound = count / 16
            for (i in 0 until totalFound) {
                flights.add(
                    FlightRecord(
                        number = 1000 + i + 1,
                        sizeBytes = 50688L * (i + 1),
                        date = "07.08.26",
                        duration = "00:08:48",
                        startTime = "10:09:46",
                        endTime = "-",
                        flightNum = "9336",
                        tailNum = "22469"
                    )
                )
            }
            return flights.takeLast(limit)
        }

        return emptyList()
    }

    private fun copySelectedFlight() {
        val rec = selectedRecord ?: return
        log("Запуск скачивания включения №${rec.number}...")
        // Логика скачивания конкретного включения
    }

    private fun executeFullDumpCommand() {
        btnStart.isEnabled = false
        progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) return@launch

            val driver = drivers[0]
            val connection = usbManager.openDevice(driver.device) ?: return@launch
            val port = driver.ports[0]

            try {
                val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
                val baud = prefs.getInt("baud_rate", 115200)
                port.open(connection)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                downloadFullDump(port)
            } catch (e: Exception) {
                log("Ошибка дампа: ${e.message}")
            } finally {
                try { port.close() } catch (_: Exception) {}
                resetUi()
            }
        }
    }

    private fun downloadFullDump(port: UsbSerialPort) {
        log("Отправка команды 'M' (0x4D) для считывания всей памяти...")
        port.write(byteArrayOf(0x4D.toByte()), 1000)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ZBN_DUMP_$timeStamp.bin"
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
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

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val sysTypes = resources.getStringArray(R.array.system_types)
        val arincTypes = resources.getStringArray(R.array.arinc_types)
        val regSpeeds = resources.getStringArray(R.array.reg_speeds)

        val sysType = sysTypes.getOrElse(prefs.getInt("system_type", 0)) { "МСРП-А-02" }
        val arinc = arincTypes.getOrElse(prefs.getInt("arinc", 0)) { "717" }
        val regSpeed = regSpeeds.getOrElse(prefs.getInt("reg_speed", 0)) { "128" }

        log("Применение схемы кадра: $sysType | ARINC-$arinc | $regSpeed поз./с")

        saveFlightMetadata(fileName.removeSuffix(".bin"), sysType, arinc, regSpeed)

        log("УСПЕХ! Файл сохранен: $fileName")
        updateStatus("Статус: Готово ($totalBytes Б)")
    }

    private fun saveFlightMetadata(
        binFileName: String, 
        sysType: String, 
        arinc: String, 
        regSpeed: String
    ) {
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val metaFile = File(downloadsDir, "$binFileName.meta")

        val metaContent = """
            ========================================
            МЕТАДАННЫЕ ПОЛЁТНОЙ ИНФОРМАЦИИ
            ========================================
            Имя файла дампа: $binFileName.bin
            Дата скачивания: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
            
            ПАРАМЕТРЫ СИСТЕМЫ:
            • Тип системы регистрации: $sysType
            • Протокол ARINC: $arinc
            • Скорость регистрации: $regSpeed поз./с
            • Длина субкадра: $regSpeed слов
            ========================================
        """.trimIndent()

        metaFile.writeText(metaContent)
        log("Метаданные сохранены: ${metaFile.name}")
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

        // 3. Статус
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

        // 6. Окно логов с прокруткой
        val scrollView = ScrollView(this)
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
        tvStatus.text = "Статус: Чтение настроек и подключение..."

        // Считываем сохраненные настройки
        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val currentBaudRate = prefs.getInt("baud_rate", 115200)
        val limitStr = prefs.getString("limit", "10") ?: "10"
        val sessionLimit = limitStr.toIntOrNull() ?: 10

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

                log("Запрос каталога включений (лимит: $sessionLimit)...")
                val flightList = readCatalog(port, sessionLimit)

                if (flightList.isEmpty()) {
                    log("Включения не найдены или выполняется дамп всей памяти...")
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

    private fun readCatalog(port: UsbSerialPort, limit: Int): List<String> {
        val flights = mutableListOf<String>()

        log("Отправка команды запроса оглавления...")
        port.write(byteArrayOf('I'.code.toByte()), 1000)

        val buffer = ByteArray(256)
        val count = port.read(buffer, 1500)

        if (count > 0) {
            val totalFoundInMemory = count / 16
            for (i in 0 until totalFoundInMemory) {
                flights.add("Включение №${i + 1}")
            }
            return flights.takeLast(limit)
        }

        return emptyList()
    }

    private fun downloadFullDump(port: UsbSerialPort) {
        log("Отправка команды 'M' (0x4D) для считывания всей памяти...")
        port.write(byteArrayOf(0x4D.toByte()), 1000)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ZBN_DUMP_$timeStamp.bin"
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
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

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val sysTypes = resources.getStringArray(R.array.system_types)
        val arincTypes = resources.getStringArray(R.array.arinc_types)
        val regSpeeds = resources.getStringArray(R.array.reg_speeds)

        val sysType = sysTypes.getOrElse(prefs.getInt("system_type", 0)) { "МСРП-А-02" }
        val arinc = arincTypes.getOrElse(prefs.getInt("arinc", 0)) { "717" }
        val regSpeed = regSpeeds.getOrElse(prefs.getInt("reg_speed", 0)) { "128" }

        log("Применение схемы кадра: $sysType | ARINC-$arinc | $regSpeed поз./с")

        saveFlightMetadata(fileName.removeSuffix(".bin"), sysType, arinc, regSpeed)

        log("УСПЕХ! Файл сохранен: Загрузки/$fileName")
        updateStatus("Статус: Готово ($totalBytes Б)")
    }

    private fun saveFlightMetadata(
        binFileName: String, 
        sysType: String, 
        arinc: String, 
        regSpeed: String
    ) {
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val metaFile = File(downloadsDir, "$binFileName.meta")

        val metaContent = """
            ========================================
            МЕТАДАННЫЕ ПОЛЁТНОЙ ИНФОРМАЦИИ
            ========================================
            Имя файла дампа: $binFileName.bin
            Дата скачивания: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
            
            ПАРАМЕТРЫ СИСТЕМЫ:
            • Тип системы регистрации: $sysType
            • Протокол ARINC: $arinc
            • Скорость регистрации: $regSpeed поз./с
            • Длина субкадра: $regSpeed слов
            ========================================
        """.trimIndent()

        metaFile.writeText(metaContent)
        log("Метаданные сохранены: ${metaFile.name}")
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
