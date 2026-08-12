package com.example.zbnreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
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

    // Цветовая палитра в стиле Google Gemini (Dark Theme)
    private val COLOR_BG = Color.parseColor("#131314")
    private val COLOR_SURFACE = Color.parseColor("#1E1F20")
    private val COLOR_SURFACE_CONTAINER = Color.parseColor("#28292A")
    private val COLOR_ACCENT = Color.parseColor("#A8C7FA")
    private val COLOR_ACCENT_TEXT = Color.parseColor("#041E49")
    private val COLOR_TEXT = Color.parseColor("#E3E3E3")
    private val COLOR_TEXT_MUTED = Color.parseColor("#757775")
    private val COLOR_BORDER = Color.parseColor("#444746")
    private val COLOR_DISABLED_BG = Color.parseColor("#181819")

    // Статусные цвета для строк таблицы
    private val COLOR_DOWNLOADED = Color.parseColor("#1A3852")
    private val COLOR_ERROR = Color.parseColor("#4A2828")

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
    private val downloadedRecordNumbers = mutableSetOf<Int>()
    private val errorRecordNumbers = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Глобальный перехватчик критических сбоев приложения
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("КРИТИЧЕСКИЙ СБОЙ ПРИЛОЖЕНИЯ в потоке ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        checkAndRequestStoragePermissions()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(COLOR_BG)
        }

        // 1. ВЕРХНЯЯ ПАНЕЛЬ
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        tvStatus = TextView(this).apply {
            text = "Статус: Подключите ЗБН и нажмите «Начать сканирование»"
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 12, 0)
            }
        }

        val btnSettings = Button(this).apply {
            text = "Настройки"
            textSize = 12f
            setTextColor(COLOR_TEXT)
            background = createRoundedDrawable(COLOR_SURFACE, 18f, COLOR_BORDER, 1)
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
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(16, 26, 16, 26)
            setOnClickListener { startReading() }
        }
        setCustomButtonState(btnStart, true, COLOR_ACCENT, COLOR_ACCENT_TEXT, 24f)

        val startParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 14) }
        btnStart.layoutParams = startParams
        root.addView(btnStart)

        // 3. ТАБЛИЦА СПИСКА ВКЛЮЧЕНИЙ
        val tableCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(COLOR_SURFACE, 20f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(0, 0, 0, 14) }
            setPadding(10, 10, 10, 10)
        }

        val scrollTable = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val verticalScroll = ScrollView(this)
        tableLayout = TableLayout(this).apply {
            isStretchAllColumns = false
        }
        verticalScroll.addView(tableLayout)
        scrollTable.addView(verticalScroll)
        tableCard.addView(scrollTable)
        root.addView(tableCard)

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
            setPadding(0, 0, 0, 14)
        }

        val btnParams = LinearLayout.LayoutParams(0, 110, 1f).apply {
            setMargins(4, 0, 4, 0)
        }

        btnCopySelected = Button(this).apply {
            text = "Копировать"
            textSize = 12f
            setOnClickListener { copySelectedFlight() }
        }
        btnCopySelected.layoutParams = btnParams
        setCustomButtonState(btnCopySelected, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)

        btnFullDump = Button(this).apply {
            text = "ВЕСЬ ЗБН"
            textSize = 12f
            setOnClickListener { executeFullDumpCommand() }
        }
        btnFullDump.layoutParams = btnParams
        setCustomButtonState(btnFullDump, true, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)

        btnExportExcel = Button(this).apply {
            text = "В Excel"
            textSize = 12f
            setOnClickListener { exportToExcel() }
        }
        btnExportExcel.layoutParams = btnParams
        setCustomButtonState(btnExportExcel, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)

        actionPanel.addView(btnCopySelected)
        actionPanel.addView(btnFullDump)
        actionPanel.addView(btnExportExcel)
        root.addView(actionPanel)

        // 6. ОКНО КОНСОЛИ
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(COLOR_SURFACE, 20f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200
            )
            setPadding(10, 10, 10, 10)
        }

        val scrollViewLog = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        tvLog = TextView(this).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(8, 8, 8, 8)
        }
        scrollViewLog.addView(tvLog)
        logCard.addView(scrollViewLog)
        root.addView(logCard)

        setContentView(root)
        renderTableHeader()
        log("Приложение запущено. Готовность к работе.")
    }

    private fun getCustomUsbProber(): UsbSerialProber {
        val customTable = UsbSerialProber.getDefaultProbeTable()
        // Чип FTDI FT232RL
        customTable.addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
        customTable.addProduct(0x0403, 0x6010, FtdiSerialDriver::class.java)
        customTable.addProduct(0x0403, 0x6014, FtdiSerialDriver::class.java)
        customTable.addProduct(0x0403, 0x6015, FtdiSerialDriver::class.java)
        // Другие поддерживаемые адаптеры
        customTable.addProduct(0x1a86, 0x7523, Ch34xSerialDriver::class.java)
        customTable.addProduct(0x1a86, 0x5523, Ch34xSerialDriver::class.java)
        customTable.addProduct(0x067b, 0x2303, ProlificSerialDriver::class.java)
        customTable.addProduct(0x03eb, 0x204b, CdcAcmSerialDriver::class.java)
        return UsbSerialProber(customTable)
    }

    private fun findUsbDriver(usbManager: UsbManager): UsbSerialDriver? {
        val rawDeviceList = usbManager.deviceList
        log("Физически подключено USB-устройств: ${rawDeviceList.size}")
        for ((_, device) in rawDeviceList) {
            val vidHex = String.format("0x%04X", device.vendorId)
            val pidHex = String.format("0x%04X", device.productId)
            log("-> Обнаружен USB: VID=$vidHex, PID=$pidHex, Name=${device.deviceName}")
        }
        var drivers = getCustomUsbProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        }
        return drivers.firstOrNull()
    }

    // 1. Оставляем вашу основную функцию без изменений
private fun log(message: String, throwable: Throwable? = null) {
    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    val logLine = if (throwable != null) {
        "[$timeStamp] $message\nИсключение: ${throwable.localizedMessage}\n${throwable.stackTraceToString()}"
    } else {
        "[$timeStamp] $message"
    }

    android.util.Log.d("ZBN_DEBUG", logLine)

    runOnUiThread {
        try {
            tvLog.append("$logLine\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val mainFolder = File(getExternalFilesDir(null), "ZBNreader")
            if (!mainFolder.exists()) {
                mainFolder.mkdirs()
            }
            val logFile = File(mainFolder, "zbn_app_log.txt")
            logFile.appendText("$logLine\n----------------------------------------\n")
        } catch (e: Exception) {
            android.util.Log.e("ZBN_DEBUG", "Ошибка записи лога на диск: ${e.localizedMessage}")
        }
    }
}

// 2. Добавляем рядом функцию форматирования байтов, которая вызывает ваш log(...)
private fun logBytes(tag: String, bytes: ByteArray, length: Int) {
    if (length <= 0) {
        log("[$tag] Получено байт: 0 (таймаут или пустой буфер)")
        return
    }
    // Формируем HEX-представление (например: 06 00 FF 3A)
    val hex = bytes.take(length).joinToString(" ") { String.format("%02X", it) }
    // Формируем ASCII-представление для читаемых символов
    val ascii = bytes.take(length).map { 
        if (it in 32..126) it.toInt().toChar() else '.' 
    }.joinToString("")
    
    // Передаем готовый красивый строковый результат в вашу функцию log
    log("[$tag] Байт: $length | HEX: [$hex] | ASCII: [$ascii]")
}



    private fun setCustomButtonState(button: Button, enabled: Boolean, activeBg: Int, activeText: Int, radiusDp: Float) {
        button.isEnabled = enabled
        if (enabled) {
            button.background = createRoundedDrawable(activeBg, radiusDp)
            button.setTextColor(activeText)
        } else {
            button.background = createRoundedDrawable(COLOR_DISABLED_BG, radiusDp, COLOR_BORDER, 1)
            button.setTextColor(COLOR_TEXT_MUTED)
        }
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

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                createMainDirectory()
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                createMainDirectory()
            }
        }
    }

    private fun createMainDirectory() {
        try {
            val rootDir = Environment.getExternalStorageDirectory()
            val mainFolder = File(rootDir, "ZBNreader")
            if (!mainFolder.exists()) {
                mainFolder.mkdirs()
            }
        } catch (e: Exception) {
            log("Ошибка создания рабочей директории", e)
        }
    }

    private fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    log("Ошибка запроса разрешений через ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", e)
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                createMainDirectory()
            }
        } else {
            val permissions = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (permissions.isNotEmpty()) {
                requestPermissions(permissions.toTypedArray(), 102)
            } else {
                createMainDirectory()
            }
        }
    }

    private fun getAircraftFolder(tailNum: String): File {
        createMainDirectory()
        val safeTail = tailNum.trim().replace(Regex("[^a-zA-Z0-9_А-Яа-я-]"), "_").ifEmpty { "Неизвестный_Борт" }
        val rootDir = Environment.getExternalStorageDirectory()
        val mainFolder = File(rootDir, "ZBNreader")
        val aircraftFolder = File(mainFolder, "Борт_$safeTail")
        if (!aircraftFolder.exists()) {
            aircraftFolder.mkdirs()
        }
        return aircraftFolder
    }

    private fun isFlightDownloaded(record: FlightRecord): Boolean {
        return try {
            val dateFormatted = try {
                val inputFormat = SimpleDateFormat("dd.MM.yy", Locale.US)
                val parsedDate = inputFormat.parse(record.date.trim())
                SimpleDateFormat("yyyyMMdd", Locale.US).format(parsedDate ?: Date())
            } catch (_: Exception) {
                SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            }
            val fileName = "${dateFormatted}_${record.number}_НАГИБИН"
            File(getAircraftFolder(record.tailNum), fileName).exists()
        } catch (e: Exception) {
            log("Ошибка проверки существующего файла для включения №${record.number}", e)
            false
        }
    }

    private fun renderTableHeader() {
        tableLayout.removeAllViews()
        val headerRow = TableRow(this).apply {
            setBackgroundColor(COLOR_SURFACE_CONTAINER)
            setPadding(6, 10, 6, 10)
        }
        val columns = arrayOf(" № ", " Адрес/Размер ", " Дата ", " Время ", " Начало ", " Конец ", " Рейс ", " Борт ")
        for (col in columns) {
            val tv = TextView(this).apply {
                text = col
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(COLOR_ACCENT)
                setPadding(10, 4, 10, 4)
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
        setCustomButtonState(btnCopySelected, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        setCustomButtonState(btnExportExcel, records.isNotEmpty(), COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        records.forEach { record ->
            val row = TableRow(this).apply {
                setPadding(6, 8, 6, 8)
                setOnClickListener { selectRow(this, record) }
            }
            when {
                errorRecordNumbers.contains(record.number) -> row.setBackgroundColor(COLOR_ERROR)
                isFlightDownloaded(record) || downloadedRecordNumbers.contains(record.number) -> row.setBackgroundColor(COLOR_DOWNLOADED)
                else -> row.setBackgroundColor(Color.TRANSPARENT)
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
                    setTextColor(COLOR_TEXT)
                    setPadding(10, 4, 10, 4)
                    gravity = Gravity.CENTER
                }
                row.addView(tv)
            }
            tableLayout.addView(row)
        }
    }

    private fun selectRow(row: TableRow, record: FlightRecord) {
        selectedRow?.let { prevRow ->
            val prevIndex = tableLayout.indexOfChild(prevRow) - 1
            if (prevIndex >= 0 && prevIndex < flightList.size) {
                val prevRecord = flightList[prevIndex]
                when {
                    errorRecordNumbers.contains(prevRecord.number) -> prevRow.setBackgroundColor(COLOR_ERROR)
                    isFlightDownloaded(prevRecord) || downloadedRecordNumbers.contains(prevRecord.number) -> prevRow.setBackgroundColor(COLOR_DOWNLOADED)
                    else -> prevRow.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
        selectedRow = row
        selectedRow?.setBackgroundColor(COLOR_SURFACE_CONTAINER)
        selectedRecord = record
        setCustomButtonState(btnCopySelected, true, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        tvStatus.text = "Выбрано включение №${record.number}"
        log("Выбрана строка: Включение №${record.number}, Борт: ${record.tailNum}, Рейс: ${record.flightNum}")
    }

    private fun startReading() {
    setCustomButtonState(btnStart, false, COLOR_ACCENT, COLOR_ACCENT_TEXT, 24f)
    setCustomButtonState(btnFullDump, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
    progressBar.isIndeterminate = false
    progressBar.max = 100
    progressBar.progress = 0
    progressBar.visibility = View.VISIBLE
    tvLog.text = ""
    tvStatus.text = "Статус: Подключение..."
    val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
    
    val currentBaudRate = prefs.getInt("baud_rate", 921600)
    
    val sessionLimit = try {
        prefs.getInt("limit", 6)
    } catch (_: Exception) {
        prefs.getString("limit", "6")?.toIntOrNull() ?: 6
    }
    log("Запуск сканирования. Скорость: $currentBaudRate бод, Лимит: $sessionLimit")
    lifecycleScope.launch(Dispatchers.IO) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = findUsbDriver(usbManager)
        if (driver == null) {
            log("Ошибка: USB-RS422 конвертер не обнаружен или не опознан!")
            updateStatus("Статус: Ошибка (Нет адаптера)")
            resetUi()
            return@launch
        }
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

            // ⬇️ ⬇️ ⬇️ ВСТАВЛЕННЫЙ БЛОК РУКОПОЖАТИЯ С ПОДРОБНЫМ ЛОГОМ ⬇️ ⬇️ ⬇️
            
            // 1. Отправка запроса ENQ
            val enqPacket = byteArrayOf(0x05)
            port.write(enqPacket, 1000)
            log("Отправка ENQ (0x05)...")

            // 2. Чтение ответа с буфером с запасом для FTDI
            val ackBuf = ByteArray(1024)
            val readAck = port.read(ackBuf, 1000)

            // 3. Вывод подробных данных в лог (вызовет логирование байтов)
            logBytes("RX_ACK", ackBuf, readAck)

            // 4. Проверка результата
            if (readAck == 0) {
                log("Ошибка: Ответ от ЗБН не получен (таймаут, 0 байт)")
                updateStatus("Статус: Сбой рукопожатия")
                resetUi()
                return@launch
            } else {
                val responseByte = ackBuf[0] 
                if (responseByte == 0x06.toByte()) {
                    log("Успех: Получен ACK (0x06). Чтение оглавления...")
                } else {
                    log("Ошибка: Неверный ответ. Ожидался 0x06, получено: 0x${String.format("%02X", responseByte)}")
                    updateStatus("Статус: Сбой рукопожатия")
                    resetUi()
                    return@launch
                }
            }

            // ⬆️ ⬆️ ⬆️ КОНЕЦ БЛОКА РУКОПОЖАТИЯ ⬆️ ⬆️ ⬆️

            val records = readCatalog(port, sessionLimit)
            runOnUiThread {
                flightList.clear()
                flightList.addAll(records)
                updateTableUI(flightList)
            }
            if (records.isEmpty()) {
                updateStatus("Статус: Оглавление пусто")
                log("Оглавление пустое или не удалось распарсить записи.")
            } else {
                updateStatus("Статус: Загружено ${records.size} включений")
                log("Успешно прочитано включений: ${records.size}")
            }
        } catch (e: Exception) {
            log("Сбой процесса чтения оглавления", e)
            updateStatus("Статус: Сбой передачи")
        } finally {
            try { port.close() } catch (e: Exception) { log("Ошибка закрытия порта", e) }
            resetUi()
        }
    }
}


    private fun readCatalog(port: UsbSerialPort, limit: Int): List<FlightRecord> {
        val flights = mutableListOf<FlightRecord>()
        port.write(byteArrayOf(0x4D.toByte()), 1000)
        val buffer = ByteArray(16384)
        var noDataCounter = 0
        while (flights.size < limit && noDataCounter < 3) {
            try {
                val count = port.read(buffer, 1000)
                if (count > 0) {
                    noDataCounter = 0
                    val parsedRecords = tocParser.parseBuffer(buffer, count)
                    flights.addAll(parsedRecords)
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
            } catch (e: Exception) {
                log("Ошибка во время чтения оглавления из порта", e)
                break
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
        setCustomButtonState(btnCopySelected, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
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
        log("Начало копирования включения №${record.number}. Офсет: $startOffset, Размер: ${bytesToRead ?: "Неизвестен"}")
        lifecycleScope.launch(Dispatchers.IO) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = findUsbDriver(usbManager)
            if (driver == null) {
                log("Ошибка скачивания: Конвертер USB не найден")
                errorRecordNumbers.add(record.number)
                runOnUiThread { updateTableUI(flightList) }
                resetUi()
                return@launch
            }
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                log("Ошибка скачивания: Нет прав USB")
                errorRecordNumbers.add(record.number)
                runOnUiThread { updateTableUI(flightList) }
                resetUi()
                return@launch
            }
            val port = driver.ports[0]
            var outputFile: File? = null
            try {
                val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
                val baud = prefs.getInt("baud_rate", 921600)
                port.open(connection)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                
                // Исправление 1: Явный сброс сигналов RS-422
                port.dtr = false
                port.rts = false

                // Исправление 2: Проверка ACK (0x06)
                port.write(byteArrayOf(0x05), 1000)
                val ack = ByteArray(1024)
                val readAck = port.read(ack, 1000)
                if (readAck <= 0 || ack[0] != 0x06.toByte()) {
                    log("Ошибка скачивания: Накопитель не ответил на рукопожатие (ACK)")
                    errorRecordNumbers.add(record.number)
                    updateStatus("Статус: Сбой рукопожатия")
                    runOnUiThread { updateTableUI(flightList) }
                    resetUi()
                    return@launch
                }

                port.write(byteArrayOf(0x4D.toByte()), 1000)
                val dateFormatted = try {
                    val inputFormat = SimpleDateFormat("dd.MM.yy", Locale.US)
                    val parsedDate = inputFormat.parse(record.date.trim())
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(parsedDate ?: Date())
                } catch (_: Exception) {
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                }
                val fileName = "${dateFormatted}_${record.number}_НАГИБИН"
                val aircraftFolder = getAircraftFolder(record.tailNum)
                outputFile = File(aircraftFolder, fileName)
                val fos = FileOutputStream(outputFile)
                val buffer = ByteArray(16384)
                var skippedBytes = 0L
                var writtenBytes = 0L
                var noDataCounter = 0
                var lastUiUpdateTime = 0L

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

                        // Исправление 3: Ограничение обновлений UI не чаще 3 раз в секунду
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUiUpdateTime > 300) {
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
                            lastUiUpdateTime = currentTime
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
                    log("Ошибка: Передача прервана. Записано $writtenBytes из $bytesToRead байт.")
                    errorRecordNumbers.add(record.number)
                    updateStatus("Статус: Ошибка (Передача прервана)")
                    if (outputFile.exists()) outputFile.delete()
                } else {
                    val sysTypes = resources.getStringArray(R.array.system_types)
                    val arincTypes = resources.getStringArray(R.array.arinc_types)
                    val regSpeeds = resources.getStringArray(R.array.reg_speeds)
                    
                    val sysType = sysTypes.getOrElse(prefs.getInt("system_type", 0)) { "БУР-1" }
                    val arinc = arincTypes.getOrElse(prefs.getInt("arinc", 0)) { "573" }
                    val regSpeed = regSpeeds.getOrElse(prefs.getInt("reg_speed", 0)) { "64" }
                    saveFlightMetadata(aircraftFolder, fileName, sysType, arinc, regSpeed)
                    downloadedRecordNumbers.add(record.number)
                    errorRecordNumbers.remove(record.number)
                    log("Успешно сохранен рейс №${record.flightNum} ($writtenBytes байт)")
                    updateStatus("Статус: Сохранен рейс №${record.flightNum}")
                }
                runOnUiThread { updateTableUI(flightList) }
            } catch (e: Exception) {
                log("Исключение при скачивании включения №${record.number}", e)
                errorRecordNumbers.add(record.number)
                updateStatus("Статус: Ошибка сбоя связи")
                outputFile?.let { if (it.exists()) it.delete() }
                runOnUiThread { updateTableUI(flightList) }
            } finally {
                try { port.close() } catch (e: Exception) { log("Ошибка закрытия порта", e) }
                resetUi()
            }
        }
    }

    private fun executeFullDumpCommand() {
        setCustomButtonState(btnStart, false, COLOR_ACCENT, COLOR_ACCENT_TEXT, 24f)
        setCustomButtonState(btnFullDump, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Статус: Чтение всего ЗБН..."
        log("Запуск чтения полного дампа ЗБН...")
        lifecycleScope.launch(Dispatchers.IO) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = findUsbDriver(usbManager)
            if (driver == null) {
                log("Ошибка: USB-RS422 конвертер не обнаружен")
                updateStatus("Статус: Ошибка (Нет адаптера)")
                resetUi()
                return@launch
            }
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                log("Ошибка: Нет доступа к USB при дампе")
                updateStatus("Статус: Ошибка доступа к USB")
                resetUi()
                return@launch
            }
            val port = driver.ports[0]
            try {
                val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
                val baud = prefs.getInt("baud_rate", 921600)
                port.open(connection)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = false
                port.rts = false
                
                downloadFullDump(port)
            } catch (e: Exception) {
                log("Сбой при выполнении полного дампа ЗБН", e)
                updateStatus("Статус: Ошибка скачивания ЗБН")
            } finally {
                try { port.close() } catch (e: Exception) { log("Ошибка закрытия порта", e) }
                resetUi()
            }
        }
    }

    private fun downloadFullDump(port: UsbSerialPort) {
        // Проверка рукопожатия перед дампом
        port.write(byteArrayOf(0x05), 1000)
        val ack = ByteArray(1024)
        val readAck = port.read(ack, 1000)
        if (readAck <= 0 || ack[0] != 0x06.toByte()) {
            log("Ошибка дампа: ЗБН не ответил (ACK не получен)")
            updateStatus("Статус: Сбой рукопожатия")
            return
        }

        port.write(byteArrayOf(0x4D.toByte()), 1000)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ZBN_DUMP_$timeStamp"
        val tailNum = flightList.firstOrNull { it.tailNum.isNotBlank() }?.tailNum ?: "Дамп"
        val aircraftFolder = getAircraftFolder(tailNum)
        val outputFile = File(aircraftFolder, fileName)
        var fos: FileOutputStream? = null
        var lastUiUpdateTime = 0L

        try {
            fos = FileOutputStream(outputFile)
            val buffer = ByteArray(16384)
            var totalBytes = 0
            var noDataCounter = 0

            while (true) {
                val count = port.read(buffer, 1000)
                if (count > 0) {
                    fos.write(buffer, 0, count)
                    totalBytes += count
                    noDataCounter = 0

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUiUpdateTime > 300) {
                        val currentTotal = totalBytes
                        runOnUiThread {
                            tvStatus.text = "Статус: Чтение всего ЗБН... ($currentTotal Б)"
                        }
                        lastUiUpdateTime = currentTime
                    }
                } else {
                    noDataCounter++
                    if (noDataCounter >= 3) break
                }
            }
            fos.flush()
            fos.close()
            if (totalBytes == 0) {
                log("Ошибка дампа: Принято 0 байт данных")
                updateStatus("Статус: Ошибка (Нет данных)")
                if (outputFile.exists()) outputFile.delete()
                return
            }
            val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
            val sysTypes = resources.getStringArray(R.array.system_types)
            val arincTypes = resources.getStringArray(R.array.arinc_types)
            val regSpeeds = resources.getStringArray(R.array.reg_speeds)
            
            val sysType = sysTypes.getOrElse(prefs.getInt("system_type", 0)) { "БУР-1" }
            val arinc = arincTypes.getOrElse(prefs.getInt("arinc", 0)) { "573" }
            val regSpeed = regSpeeds.getOrElse(prefs.getInt("reg_speed", 0)) { "64" }
            saveFlightMetadata(aircraftFolder, fileName, sysType, arinc, regSpeed)
            log("Полный дамп успешен: Сохранено $totalBytes байт в $fileName")
            updateStatus("Статус: Весь ЗБН сохранен ($totalBytes Б)")
        } catch (e: Exception) {
            log("Ошибка сохранения/чтения полного дампа", e)
            updateStatus("Статус: Ошибка записи/чтения ЗБН")
            try { fos?.close() } catch (_: Exception) {}
            if (outputFile.exists()) outputFile.delete()
        }
    }

    private fun exportToExcel() {
        if (flightList.isEmpty()) {
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
                    headerRow.createCell(index).setCellValue(header)
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
                val tailNum = flightList.firstOrNull { it.tailNum.isNotBlank() }?.tailNum ?: "Общий"
                val aircraftFolder = getAircraftFolder(tailNum)
                val outputFile = File(aircraftFolder, fileName)
                FileOutputStream(outputFile).use { fos ->
                    workbook.write(fos)
                }
                workbook.close()
                log("Таблица экспортирована в Excel: ${outputFile.absolutePath}")
                updateStatus("Статус: Excel сохранен ($fileName)")
            } catch (e: Exception) {
                log("Ошибка создания файла Excel", e)
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
        try {
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
        } catch (e: Exception) {
            log("Ошибка сохранения метаданных .meta", e)
        }
    }

    private fun updateStatus(text: String) {
        runOnUiThread { tvStatus.text = text }
    }

    private fun resetUi() {
        runOnUiThread {
            setCustomButtonState(btnStart, true, COLOR_ACCENT, COLOR_ACCENT_TEXT, 24f)
            setCustomButtonState(btnFullDump, true, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
            setCustomButtonState(btnCopySelected, selectedRecord != null, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
            setCustomButtonState(btnExportExcel, flightList.isNotEmpty(), COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            progressBar.progress = 0
        }
    }
}
