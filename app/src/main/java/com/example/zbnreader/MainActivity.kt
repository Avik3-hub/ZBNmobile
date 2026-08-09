package com.example.zbnreader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
    private lateinit var btnExportExcel: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tableLayout: TableLayout

    private val flightList = mutableListOf<FlightRecord>()
    private var selectedRecord: FlightRecord? = null
    private var selectedRow: TableRow? = null
    private val tocParser = ZbnTocParser()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.BLACK)
        }

        // 1. ВЕРХНЯЯ ПАНЕЛЬ
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }

        tvStatus = TextView(this).apply {
            text = "Статус: Подключите ЗБН и нажмите Начать Сканирование"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
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

        // 2. КНОПКА СТАРТА
        btnStart = Button(this).apply {
            text = "НАЧАТЬ СКАНИРОВАНИЕ"
            textSize = 16f
            setOnClickListener { startReading() }
        }
        root.addView(btnStart)

        // 3. ТАБЛИЦА СПИСКА ВКЛЮЧЕНИЙ
        val scrollTable = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.parseColor("#1E1E1E"))
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
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progressBar)

        // 5. ПАНЕЛЬ ДЕЙСТВИЙ
        val actionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }

        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(4, 0, 4, 0)
        }

        btnCopySelected = Button(this).apply {
            text = "Копировать"
            isEnabled = false
            layoutParams = btnParams
            setOnClickListener { copySelectedFlight() }
        }

        btnFullDump = Button(this).apply {
            text = "ВЕСЬ ЗБН"
            layoutParams = btnParams
            setOnClickListener { executeFullDumpCommand() }
        }

        btnExportExcel = Button(this).apply {
            text = "В Excel"
            isEnabled = false
            layoutParams = btnParams
            setOnClickListener { exportToExcel() }
        }

        actionPanel.addView(btnCopySelected)
        actionPanel.addView(btnFullDump)
        actionPanel.addView(btnExportExcel)
        root.addView(actionPanel)

        // 6. ОКНО КОНСОЛИ
        val scrollViewLog = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220
            )
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        tvLog = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(10, 10, 10, 10)
        }

        scrollViewLog.addView(tvLog)
        root.addView(scrollViewLog)

        setContentView(root)
        renderTableHeader()
    }

    private fun log(message: String) {
        runOnUiThread { tvLog.append("$message\n") }
    }

    // Вспомогательная функция для автоматического создания/получения папки борта
    private fun getAircraftFolder(tailNum: String): File {
        val safeTail = tailNum.trim().replace(Regex("[^a-zA-Z0-9_А-Яа-я-]"), "_").ifEmpty { "Неизвестный_Борт" }
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val aircraftFolder = File(downloadsDir, "Борт_$safeTail")
        if (!aircraftFolder.exists()) {
            aircraftFolder.mkdirs()
            log("Создана новая папка борта: ${aircraftFolder.name}")
        }
        return aircraftFolder
    }

    private fun renderTableHeader() {
        tableLayout.removeAllViews()
        val headerRow = TableRow(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(5, 8, 5, 8)
        }

        val columns = arrayOf(" № ", " Адрес/Размер ", " Дата ", " Время ", " Начало ", " Конец ", " Рейс ", " Борт ")
        for (col in columns) {
            val tv = TextView(this).apply {
                text = col
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
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
        btnExportExcel.isEnabled = records.isNotEmpty()

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
                    setTextColor(Color.WHITE)
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
        selectedRow?.setBackgroundColor(Color.parseColor("#263238"))
        selectedRecord = record
        btnCopySelected.isEnabled = true
        tvStatus.text = "Выбрано включение №${record.number}"
        log("Выбрана строка: Включение №${record.number}, Борт: ${record.tailNum}, Рейс: ${record.flightNum}")
    }

    private fun startReading() {
        btnStart.isEnabled = false
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        tvLog.text = ""
        tvStatus.text = "Статус: Подключение..."

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val currentBaudRate = prefs.getInt("baud_rate", 115200)

        val sessionLimit = try {
            prefs.getInt("limit", 10)
        } catch (_: Exception) {
            prefs.getString("limit", "10")?.toIntOrNull() ?: 10
        }

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
                log("Запрос оглавления...")

                val records = readCatalog(port, sessionLimit)
                runOnUiThread {
                    flightList.clear()
                    flightList.addAll(records)
                    updateTableUI(flightList)
                }

                if (records.isEmpty()) {
                    log("Включения не найдены.")
                    updateStatus("Статус: Оглавление пусто")
                } else {
                    log("Успешно загружено включений: ${records.size}")
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
        log("Отправка команды 'M' (0x4D)...")
        port.write(byteArrayOf(0x4D.toByte()), 1000)
        val buffer = ByteArray(512)
        var noDataCounter = 0

        while (flights.size < limit && noDataCounter < 3) {
            val count = port.read(buffer, 1000)
            if (count > 0) {
                noDataCounter = 0
                val parsedRecords = tocParser.parseBuffer(buffer, count)
                flights.addAll(parsedRecords)
                log("Принято байт: $count | Считано включений: ${flights.size} / $limit")

                val currentCount = flights.size.coerceAtMost(limit)
                val percent = ((currentCount * 100) / limit).coerceAtMost(100)
                runOnUiThread {
                    progressBar.progress = percent
                    tvStatus.text = "Статус: Поиск включений ($currentCount из $limit)... $percent%"
                }

                if (flights.size >= limit) break
            } else {
                noDataCounter++
            }
        }
        return flights.take(limit)
    }

    private fun copySelectedFlight() {
        val record = selectedRecord ?: return

        val currentIndex = flightList.indexOf(record)
        val startOffset = record.sizeBytes

        val bytesToRead: Long? = if (currentIndex >= 0 && currentIndex < flightList.size - 1) {
            flightList[currentIndex + 1].sizeBytes - startOffset
        } else {
            null
        }

        btnCopySelected.isEnabled = false
        progressBar.visibility = View.VISIBLE

        if (bytesToRead != null && bytesToRead > 0) {
            progressBar.isIndeterminate = false
            progressBar.max = 100
            progressBar.progress = 0
            tvStatus.text = "Статус: Скачивание №${record.number} (0%)..."
        } else {
            progressBar.isIndeterminate = true
            tvStatus.text = "Статус: Скачивание №${record.number}..."
        }

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
                log("Ошибка доступа к USB!")
                updateStatus("Статус: Ошибка доступа к USB")
                resetUi()
                return@launch
            }

            val port = driver.ports[0]
            var outputFile: File? = null

            try {
                val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
                val baud = prefs.getInt("baud_rate", 115200)

                port.open(connection)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                port.write(byteArrayOf(0x05), 1000)
                val ack = ByteArray(1)
                port.read(ack, 1000)

                port.write(byteArrayOf(0x4D.toByte()), 1000)

                val dateFormatted = try {
                    val inputFormat = SimpleDateFormat("dd.MM.yy", Locale.US)
                    val parsedDate = inputFormat.parse(record.date.trim())
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(parsedDate ?: Date())
                } catch (e: Exception) {
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                }

                val fileName = "${dateFormatted}_${record.number}_НАГИБИН"
                
                // СОХРАНЕНИЕ В ПАПКУ БОРТА
                val aircraftFolder = getAircraftFolder(record.tailNum)
                outputFile = File(aircraftFolder, fileName)
                val fos = FileOutputStream(outputFile)

                val buffer = ByteArray(512)
                var skippedBytes = 0L
                var writtenBytes = 0L
                var noDataCounter = 0

                while (true) {
                    val count = port.read(buffer, 1000)
                    if (count > 0) {
                        noDataCounter = 0

                        if (skippedBytes < startOffset) {
                            val neededToSkip = startOffset - skippedBytes
                            if (count <= neededToSkip) {
                                skippedBytes += count
                                continue
                            } else {
                                val validDataStart = neededToSkip.toInt()
                                val validLength = count - validDataStart
                                skippedBytes = startOffset

                                val bytesToWrite = if (bytesToRead != null && (writtenBytes + validLength) > bytesToRead) {
                                    (bytesToRead - writtenBytes).toInt()
                                } else {
                                    validLength
                                }
                                fos.write(buffer, validDataStart, bytesToWrite)
                                writtenBytes += bytesToWrite
                            }
                        } else {
                            val bytesToWrite = if (bytesToRead != null && (writtenBytes + count) > bytesToRead) {
                                (bytesToRead - writtenBytes).toInt()
                            } else {
                                count
                            }
                            fos.write(buffer, 0, bytesToWrite)
                            writtenBytes += bytesToWrite
                        }

                        val currentWritten = writtenBytes
                        runOnUiThread {
                            if (bytesToRead != null && bytesToRead > 0) {
                                val percent = ((currentWritten * 100) / bytesToRead).toInt().coerceAtMost(100)
                                progressBar.progress = percent
                                tvStatus.text = "Статус: Скачивание №${record.number}... $percent%"
                            } else {
                                tvStatus.text = "Статус: Скачивание №${record.number}... ($currentWritten Б)"
                            }
                        }

                        if (bytesToRead != null && writtenBytes >= bytesToRead) break
                    } else {
                        noDataCounter++
                        if (noDataCounter >= 3) break
                    }
                }

                fos.flush()
                fos.close()

                if (bytesToRead != null && writtenBytes < bytesToRead) {
                    log("ОШИБКА: Скачивание прервано! Записано $writtenBytes из $bytesToRead Б")
                    updateStatus("Статус: Ошибка (Передача прервана)")
                    if (outputFile.exists()) outputFile.delete()
                } else {
                    val sysTypes = resources.getStringArray(R.array.system_types)
                    val arincTypes = resources.getStringArray(R.array.arinc_types)
                    val regSpeeds = resources.getStringArray(R.array.reg_speeds)

                    val sysType = sysTypes.getOrElse(prefs.getInt("system_type", 0)) { "МСРП-А-02" }
                    val arinc = arincTypes.getOrElse(prefs.getInt("arinc", 0)) { "717" }
                    val regSpeed = regSpeeds.getOrElse(prefs.getInt("reg_speed", 0)) { "128" }

                    saveFlightMetadata(aircraftFolder, fileName, sysType, arinc, regSpeed)
                    log("УСПЕХ! Включение №${record.number} сохранено в папку 'Борт_${record.tailNum}'")
                    updateStatus("Статус: Сохранен рейс №${record.flightNum}")
                }

            } catch (e: Exception) {
                log("Ошибка при выгрузке полета: ${e.message}")
                updateStatus("Статус: Ошибка сбоя связи")
                outputFile?.let { if (it.exists()) it.delete() }
            } finally {
                try { port.close() } catch (_: Exception) {}
                resetUi()
            }
        }
    }

    private fun executeFullDumpCommand() {
        btnStart.isEnabled = false
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Статус: Чтение всего ЗБН..."

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
                log("Ошибка доступа к USB!")
                updateStatus("Статус: Ошибка доступа к USB")
                resetUi()
                return@launch
            }

            val port = driver.ports[0]

            try {
                val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
                val baud = prefs.getInt("baud_rate", 115200)

                port.open(connection)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                downloadFullDump(port)
            } catch (e: Exception) {
                log("Ошибка считывания ЗБН: ${e.message}")
                updateStatus("Статус: Ошибка скачивания ЗБН")
            } finally {
                try { port.close() } catch (_: Exception) {}
                resetUi()
            }
        }
    }

    private fun downloadFullDump(port: UsbSerialPort) {
        log("Отправка команды 'M' (0x4D)...")
        port.write(byteArrayOf(0x4D.toByte()), 1000)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ZBN_DUMP_$timeStamp"
        
        // Определяем борт по считанному списку (если есть)
        val tailNum = flightList.firstOrNull { it.tailNum.isNotBlank() }?.tailNum ?: "Дамп"
        val aircraftFolder = getAircraftFolder(tailNum)
        val outputFile = File(aircraftFolder, fileName)
        var fos: FileOutputStream? = null

        try {
            fos = FileOutputStream(outputFile)
            val buffer = ByteArray(512)
            var totalBytes = 0
            var noDataCounter = 0

            while (true) {
                val count = port.read(buffer, 1000)
                if (count > 0) {
                    fos.write(buffer, 0, count)
                    totalBytes += count
                    noDataCounter = 0

                    val currentTotal = totalBytes
                    runOnUiThread {
                        tvStatus.text = "Статус: Чтение всего ЗБН... ($currentTotal Б)"
                    }
                } else {
                    noDataCounter++
                    if (noDataCounter >= 3) break
                }
            }

            fos.flush()
            fos.close()

            if (totalBytes == 0) {
                log("ОШИБКА: Данные не получены!")
                updateStatus("Статус: Ошибка (Нет данных)")
                if (outputFile.exists()) outputFile.delete()
                return
            }

            val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
            val sysTypes = resources.getStringArray(R.array.system_types)
            val arincTypes = resources.getStringArray(R.array.arinc_types)
            val regSpeeds = resources.getStringArray(R.array.reg_speeds)

            val sysType = sysTypes.getOrElse(prefs.getInt("system_type", 0)) { "МСРП-А-02" }
            val arinc = arincTypes.getOrElse(prefs.getInt("arinc", 0)) { "717" }
            val regSpeed = regSpeeds.getOrElse(prefs.getInt("reg_speed", 0)) { "128" }

            saveFlightMetadata(aircraftFolder, fileName, sysType, arinc, regSpeed)
            log("УСПЕХ! Весь ЗБН сохранен в папку '${aircraftFolder.name}' ($totalBytes Б)")
            updateStatus("Статус: Весь ЗБН сохранен ($totalBytes Б)")

        } catch (e: Exception) {
            log("Ошибка при чтении ЗБН: ${e.message}")
            updateStatus("Статус: Ошибка записи/чтения ЗБН")
            try { fos?.close() } catch (_: Exception) {}
            if (outputFile.exists()) outputFile.delete()
        }
    }

    // ЭКСПОРТ ТАБЛИЦЫ В EXCEL (.XLSX) В ПАПКУ БОРТА
    private fun exportToExcel() {
        if (flightList.isEmpty()) {
            log("Ошибка: Таблица пуста!")
            updateStatus("Статус: Таблица пуста")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val workbook = XSSFWorkbook()
                val sheet = workbook.createSheet("Лист1")

                val headerRow = sheet.createRow(0)
                val headers = arrayOf("№", "Размер", "Дата", "ВремяЗап", "Начало", "Конец", "Рейс", "Борт", "Сбоев")
                for ((index, header) in headers.withIndex()) {
                    val cell = headerRow.createCell(index)
                    cell.setCellValue(header)
                }

                for ((rowIndex, record) in flightList.withIndex()) {
                    val row = sheet.createRow(rowIndex + 1)
                    row.createCell(0).setCellValue(record.number.toDouble())
                    row.createCell(1).setCellValue(record.sizeBytes.toDouble())
                    row.createCell(2).setCellValue(record.date)
                    row.createCell(3).setCellValue(record.duration)
                    row.createCell(4).setCellValue(record.startTime)
                    row.createCell(5).setCellValue(record.endTime)
                    
                    record.flightNum.toDoubleOrNull()?.let { row.createCell(6).setCellValue(it) } 
                        ?: row.createCell(6).setCellValue(record.flightNum)
                        
                    record.tailNum.toDoubleOrNull()?.let { row.createCell(7).setCellValue(it) } 
                        ?: row.createCell(7).setCellValue(record.tailNum)

                    row.createCell(8).setCellValue("")
                }

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "Список_включений_$timeStamp.xlsx"

                // Сохранение в папку первого борта из списка
                val tailNum = flightList.firstOrNull { it.tailNum.isNotBlank() }?.tailNum ?: "Общий"
                val aircraftFolder = getAircraftFolder(tailNum)
                val outputFile = File(aircraftFolder, fileName)

                FileOutputStream(outputFile).use { fos ->
                    workbook.write(fos)
                }
                workbook.close()

                log("УСПЕХ! Excel сохранен в папку '${aircraftFolder.name}': $fileName")
                updateStatus("Статус: Excel сохранен ($fileName)")

            } catch (e: Exception) {
                log("Ошибка экспорта в Excel: ${e.message}")
                updateStatus("Статус: Ошибка создания Excel")
            }
        }
    }

    private fun saveFlightMetadata(
        folder: File,
        binFileName: String,
        sysType: String,
        arinc: String,
        regSpeed: String
    ) {
        val metaFile = File(folder, "$binFileName.meta")

        val metaContent = """
            ========================================
            МЕТАДАННЫЕ ПОЛЁТНОЙ ИНФОРМАЦИИ
            ========================================
            Имя файла дампа: $binFileName
            Дата скачивания: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
            
            ПАРАМЕТРЫ СИСТЕМЫ:
            • Тип системы регистрации: $sysType
            • Протокол ARINC: $arinc
            • Скорость регистрации: $regSpeed поз./с
            • Длина субкадра: $regSpeed слов
            ========================================
        """.trimIndent()

        metaFile.writeText(metaContent)
        log("Метаданные сохранены в '${folder.name}/${metaFile.name}'")
    }

    private fun updateStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
    }

    private fun resetUi() {
        runOnUiThread {
            btnStart.isEnabled = true
            btnCopySelected.isEnabled = selectedRecord != null
            btnExportExcel.isEnabled = flightList.isNotEmpty()
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            progressBar.progress = 0
        }
    }
}
