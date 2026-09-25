package com.example.zbnreader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
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
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    // Тёмная авиационная палитра: AMOLED, графит, холодный голубой и янтарный акцент.
    private val COLOR_BG = Color.parseColor("#080A0D")
    private val COLOR_SURFACE = Color.parseColor("#14181D")
    private val COLOR_SURFACE_CONTAINER = Color.parseColor("#20262D")
    private val COLOR_ACCENT = Color.parseColor("#A8C7FA")
    private val COLOR_ACCENT_TEXT = Color.parseColor("#071526")
    private val COLOR_AMBER = Color.parseColor("#F1B45B")
    private val COLOR_TEXT = Color.parseColor("#F2F5F7")
    private val COLOR_TEXT_MUTED = Color.parseColor("#89929C")
    private val COLOR_BORDER = Color.parseColor("#343C45")
    private val COLOR_DISABLED_BG = Color.parseColor("#101317")

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
    private lateinit var tableScroll: HorizontalScrollView
    private lateinit var headerTailValue: TextView
    private lateinit var mi171Pet: Mi171PetView
    private lateinit var petSpeech: PetSpeechView
    private var observedUsbId: Int? = null
    private var usbReceiverRegistered = false
    private var catalogReadProblem = false
    private val petUsbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshPetUsb()
        }
    }

    private fun petSay(text: String, urgent: Boolean = false, requiresUsb: Boolean = false) {
        runOnUiThread {
            if (::petSpeech.isInitialized && !isDestroyed && (!requiresUsb || observedUsbId != null)) petSpeech.say(text, urgent)
        }
    }

    private fun refreshPetUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = getCustomUsbProber().findAllDrivers(manager)
        val old = observedUsbId
        val device = drivers.firstOrNull { it.device.deviceId == old } ?: drivers.firstOrNull()
        val id = device?.device?.deviceId
        if (old == id) {
            petSpeech.setConnected(id != null)
            return
        }
        observedUsbId = id
        petSpeech.setConnected(id != null)
        if (id == null) petSay("Кабель отключён. Жду подключения", true)
        else petSay("USB вижу. Жду ЗБН")
    }

    override fun onDestroy() {
        if (usbReceiverRegistered) unregisterReceiver(petUsbReceiver)
        super.onDestroy()
    }

    private val flightList = mutableListOf<FlightRecord>()
    private var selectedRecord: FlightRecord? = null
    private var selectedRow: TableRow? = null
    private val tocParser = ZbnTocParser()
    private var readingMetadata = false
    private val flightCopyVerified = true
    private val fullDumpVerified = false
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
            setPadding(dp(12), dp(6), dp(12), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 1. ШАПКА И СТАТУС
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), 0, 0)
        }
        titleBlock.addView(TextView(this).apply {
            text = "ZBN Mobile"
            textSize = 22f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            setTextColor(COLOR_TEXT)
        })
        titleBlock.addView(TextView(this).apply {
            text = "СНЯТИЕ ПОЛЁТНОЙ ИНФОРМАЦИИ"
            textSize = 9.5f
            letterSpacing = 0.08f
            setTextColor(COLOR_AMBER)
        })
        titleBlock.addView(TextView(this).apply {
            text = "Ми-8 АМТ"
            textSize = 8.5f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(3), 0, 0)
        })
        headerTailValue = TextView(this).apply {
            textSize = 8.5f
            setTextColor(COLOR_TEXT_MUTED)
        }
        titleBlock.addView(headerTailValue)
        val btnSettings = ImageButton(this).apply {
            setImageResource(R.drawable.ic_more_vertical)
            background = createRoundedDrawable(Color.TRANSPARENT, 0f)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(6), dp(9), dp(6))
            contentDescription = "Настройки"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        val headerFrame = FrameLayout(this).apply {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.zbn_blueprint_mi171)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setColorFilter(Color.parseColor("#4F8BC4"), PorterDuff.Mode.SRC_ATOP)
                alpha = 0.90f
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(224), dp(88), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(8)
            })
            addView(titleBlock, FrameLayout.LayoutParams(
                dp(210), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP
            ))
            addView(btnSettings, FrameLayout.LayoutParams(
                dp(38), dp(38), Gravity.END or Gravity.TOP
            ))
        }
        root.addView(headerFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(88)
        ))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(9), dp(4))
            background = createRoundedDrawable(COLOR_SURFACE, 8f, COLOR_BORDER, 1)
        }
        statusCard.addView(TextView(this).apply {
            text = "USB"
            textSize = 9f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            setTextColor(COLOR_ACCENT)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(30), dp(22)))
        tvStatus = TextView(this).apply {
            text = "Подключите ЗБН и нажмите «Начать сканирование»"
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            maxLines = 2
            setPadding(dp(5), 0, dp(6), 0)
        }
        statusCard.addView(tvStatus, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))
        statusCard.addView(TextView(this).apply {
            text = "!"
            textSize = 13f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            setTextColor(COLOR_AMBER)
            gravity = Gravity.CENTER
            background = createRoundedDrawable(Color.TRANSPARENT, 8f, COLOR_AMBER, 1)
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        root.addView(statusCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(7) })

        // 2. КНОПКА СТАРТА
        btnStart = Button(this).apply {
            text = "НАЧАТЬ СКАНИРОВАНИЕ"
            textSize = 14f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startReading() }
        }
        setCustomButtonState(btnStart, true, COLOR_ACCENT, COLOR_ACCENT_TEXT, 7f)
        val startParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply { setMargins(0, 0, 0, dp(7)) }
        btnStart.layoutParams = startParams
        root.addView(btnStart)

        // 3. ТАБЛИЦА СПИСКА ВКЛЮЧЕНИЙ
        val tableCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(COLOR_SURFACE, 10f, COLOR_BORDER, 1)
            minimumHeight = dp(230)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply {
                setMargins(0, 0, 0, dp(7))
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        tableCard.addView(TextView(this).apply {
            text = "ВКЛЮЧЕНИЯ ЗБН"
            textSize = 16f
            typeface = resources.getFont(R.font.zbn_sans_bold)
            letterSpacing = 0.08f
            setTextColor(COLOR_AMBER)
            setPadding(dp(6), dp(3), dp(6), dp(8))
        })
        tableScroll = HorizontalScrollView(this).apply {
            isSaveEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val verticalScroll = ScrollView(this)
        tableLayout = TableLayout(this).apply {
            isStretchAllColumns = false
        }
        verticalScroll.addView(tableLayout)
        tableScroll.addView(verticalScroll)
        tableCard.addView(tableScroll)
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
            setPadding(0, 0, 0, dp(4))
        }
        val btnParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        btnCopySelected = Button(this).apply {
            text = "Копировать"
            textSize = 10.5f
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_copy, 0, 0, 0)
            compoundDrawablePadding = dp(2)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { copySelectedFlight() }
        }
        btnCopySelected.layoutParams = btnParams
        setCustomButtonState(btnCopySelected, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)

        btnFullDump = Button(this).apply {
            text = "ВЕСЬ ЗБН"
            textSize = 10.5f
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_storage, 0, 0, 0)
            compoundDrawablePadding = dp(2)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { executeFullDumpCommand() }
        }
        btnFullDump.layoutParams = btnParams
        setCustomButtonState(btnFullDump, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)

        btnExportExcel = Button(this).apply {
            text = "В Excel"
            textSize = 10.5f
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_table, 0, 0, 0)
            compoundDrawablePadding = dp(2)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { exportToExcel() }
        }
        btnExportExcel.layoutParams = btnParams
        setCustomButtonState(btnExportExcel, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)

        actionPanel.addView(btnCopySelected)
        actionPanel.addView(btnFullDump)
        actionPanel.addView(btnExportExcel)
        root.addView(actionPanel)

        // 6. ЖУРНАЛ И ВЕРТОЛЁТ: изображение намеренно заходит на журнал.
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(COLOR_SURFACE, 10f, COLOR_BORDER, 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        logCard.addView(TextView(this).apply {
            text = "ЖУРНАЛ СЕАНСА"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(COLOR_AMBER)
            setPadding(dp(2), 0, dp(2), dp(4))
        })
        val scrollViewLog = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        tvLog = TextView(this).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(8, 8, 8, 8)
        }
        scrollViewLog.addView(tvLog)
        logCard.addView(scrollViewLog)

        val lowerPanel = FrameLayout(this).apply {
            clipChildren = false
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.zbn_perspective_grid)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.82f
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(logCard, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(100),
                Gravity.TOP
            ))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.zbn_mi171_scene)
                scaleType = ImageView.ScaleType.FIT_END
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(320), dp(136), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = 0
                bottomMargin = dp(-7)
            })
        }
        root.addView(lowerPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(184)
        ).apply {
            topMargin = dp(1)
        })

        val screen = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BG)
            addView(AviationBackdropView(this@MainActivity), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        mi171Pet = Mi171PetView(this).apply {
            elevation = 12f
        }
        val petWidth = (96 * resources.displayMetrics.density).toInt()
        val petHeight = (104 * resources.displayMetrics.density).toInt()
        screen.addView(mi171Pet, FrameLayout.LayoutParams(petWidth, petHeight, Gravity.END or Gravity.BOTTOM).apply {
            val sideMargin = (12 * resources.displayMetrics.density).toInt()
            // Keep the first-launch position above the action buttons. A position
            // chosen by dragging is still restored and may be anywhere on screen.
            val defaultBottomMargin = (160 * resources.displayMetrics.density).toInt()
            setMargins(sideMargin, sideMargin, sideMargin, defaultBottomMargin)
        })

        petSpeech = PetSpeechView(this, mi171Pet).apply { elevation = 13f }
        screen.addView(petSpeech, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ContextCompat.registerReceiver(this, petUsbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        usbReceiverRegistered = true
        val scanPage = DocumentScanPage(this)
        val pager = ViewPager2(this).apply {
            adapter = StaticPagesAdapter(listOf(screen, scanPage))
            offscreenPageLimit = 1
        }
        val firstTab = pageTab("Снятие ПИ", true)
        val secondTab = pageTab("Паспорт БУР-1", false)
        val pageIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = createRoundedDrawable(COLOR_SURFACE, 22f, COLOR_BORDER, 1)
            elevation = dp(8).toFloat()
            addView(firstTab)
            addView(secondTab)
        }
        firstTab.setOnClickListener { pager.setCurrentItem(0, true) }
        secondTab.setOnClickListener { pager.setCurrentItem(1, true) }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                stylePageTab(firstTab, position == 0)
                stylePageTab(secondTab, position == 1)
                if (position == 1) scanPage.refresh()
            }
        })
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            addView(pager, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(pageIndicator, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(16), dp(3), dp(16), dp(7))
            })
        })
        renderTableHeader()
        refreshHeaderTail()
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

    private fun logBytes(tag: String, bytes: ByteArray, length: Int) {
        if (length <= 0) {
            log("[$tag] Получено байт: 0 (таймаут или пустой буфер)")
            return
        }
        val hex = bytes.take(length).joinToString(" ") { String.format("%02X", it) }
        val ascii = bytes.take(length).map { 
            if (it in 32..126) it.toInt().toChar() else '.' 
        }.joinToString("")
        log("[$tag] Байт: $length | HEX: [$hex] | ASCII: [$ascii]")
    }

    private fun setCustomButtonState(button: Button, enabled: Boolean, activeBg: Int, activeText: Int, radiusDp: Float) {
        button.isEnabled = enabled
        val cornerRadius = radiusDp.coerceAtMost(8f)
        if (enabled) {
            button.background = createRoundedDrawable(activeBg, cornerRadius)
            button.setTextColor(activeText)
        } else {
            button.background = createRoundedDrawable(COLOR_DISABLED_BG, cornerRadius, COLOR_BORDER, 1)
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
        if (::headerTailValue.isInitialized) refreshHeaderTail()
        if (::mi171Pet.isInitialized) {
            val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
            if (!prefs.getBoolean("visual_refresh_pet_default_applied", false)) {
                prefs.edit()
                    .putBoolean("mi171_pet_enabled", false)
                    .putBoolean("visual_refresh_pet_default_applied", true)
                    .apply()
            }
            val enabled = prefs.getBoolean("mi171_pet_enabled", false)
            mi171Pet.setSmallSize(prefs.getBoolean("mi171_pet_small", false))
            mi171Pet.setPetEnabled(enabled)
            petSpeech.setHelperEnabled(enabled)
            refreshPetUsb()
        }
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
            setPadding(0, 0, 0, 0)
        }
        val columns = arrayOf(" № ", " Адрес/Размер ", " Дата ", " Время ", " Начало ", " Конец ", " Рейс ", " Борт ")
        for (col in columns) {
            val tv = TextView(this).apply {
                text = col
                textSize = 12f
                typeface = resources.getFont(R.font.zbn_sans_bold)
                setTextColor(COLOR_ACCENT)
                setPadding(dp(7), dp(5), dp(7), dp(5))
                gravity = Gravity.CENTER
                background = tableCellDrawable(COLOR_SURFACE_CONTAINER)
            }
            headerRow.addView(tv)
        }
        tableLayout.addView(headerRow)
        if (::tableScroll.isInitialized) {
            tableScroll.post { tableScroll.scrollTo(0, 0) }
        }
    }

    private fun updateTableUI(records: List<FlightRecord>) {
        renderTableHeader()
        selectedRecord = null
        selectedRow = null
        setCustomButtonState(btnCopySelected, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        setCustomButtonState(btnExportExcel, records.isNotEmpty(), COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        records.forEach { record ->
            val row = TableRow(this).apply {
                setPadding(0, 0, 0, 0)
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
                    setPadding(dp(7), dp(5), dp(7), dp(5))
                    gravity = Gravity.CENTER
                    background = tableCellDrawable(Color.TRANSPARENT)
                }
                row.addView(tv)
            }
            tableLayout.addView(row)
        }
    }

    private fun selectRow(row: TableRow, record: FlightRecord) {
        if (readingMetadata) return
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
        setCustomButtonState(btnCopySelected, flightCopyVerified, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        tvStatus.text = "Выбрано включение №${record.number}"
        log("Выбрана строка: Включение №${record.number}, Борт: ${record.tailNum}, Рейс: ${record.flightNum}")
    }

    private fun startReading() {
        mi171Pet.setBusy(true)
        mi171Pet.play("waiting")
        refreshPetUsb()
        setCustomButtonState(btnStart, false, COLOR_ACCENT, COLOR_ACCENT_TEXT, 24f)
        setCustomButtonState(btnFullDump, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        tvLog.text = ""
        tvStatus.text = "Статус: Подключение..."
        
        // Очищаем старые данные из интерфейса перед новым сканированием
        flightList.clear()
        renderTableHeader()

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        val currentBaudRate = prefs.getInt("baud_rate", 115200)
        
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
                petSay("Не вижу USB. Проверь адаптер и кабель", true)
                updateStatus("Статус: Ошибка (Нет адаптера)")
                resetUi()
                return@launch
            }
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                log("Ошибка: Нет разрешения на использование USB!")
                petSay("Нет доступа к USB. Разреши подключение", true)
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
                
                // 1. Отправка запроса ENQ
                val enqPacket = byteArrayOf(0x05)
                port.write(enqPacket, 4000)
                log("Отправка ENQ (0x05)...")

                // 2. Чтение ответа с буфером с запасом для FTDI
                val ackBuf = ByteArray(1024)
                val readAck = port.read(ackBuf, 4000)
                
                logBytes("RX_ACK", ackBuf, readAck)

                // 3. Проверка результата
                if (readAck == 0) {
                    log("Ошибка: Ответ от ЗБН не получен (таймаут, 0 байт)")
                    petSay("ЗБН не отвечает. Проверь питание и подключение", true)
                    updateStatus("Статус: Сбой рукопожатия")
                    resetUi()
                    return@launch
                } else {
                    val responseByte = ackBuf[0] 
                    if (responseByte == 0x06.toByte()) {
                        log("Успех: Получен ACK (0x06). Чтение оглавления...")
                        petSay("Ищу полёты", requiresUsb = true)
                    } else {
                        log("Ошибка: Неверный ответ. Ожидался 0x06, получено: 0x${String.format("%02X", responseByte)}")
                        petSay("ЗБН не отвечает. Проверь питание и подключение", true)
                        updateStatus("Статус: Сбой рукопожатия")
                        resetUi()
                        return@launch
                    }
                }

                val records = readCatalog(port, sessionLimit, currentBaudRate) {
                    // The reference PC program starts every metadata request
                    // in a fresh serial-port session.
                    try {
                        port.close()
                    } catch (_: Exception) {
                        // The next open below is the authoritative state check.
                    }
                    val metadataConnection = usbManager.openDevice(driver.device)
                        ?: throw IllegalStateException("Нет доступа к USB для чтения подписей")
                    try {
                        port.open(metadataConnection)
                    } catch (e: Exception) {
                        metadataConnection.close()
                        throw e
                    }
                    port.setParameters(
                        currentBaudRate,
                        8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                    port.dtr = false
                    port.rts = false
                }
                runOnUiThread {
                    flightList.clear()
                    flightList.addAll(records)
                    rememberLastTailNumber(records)
                    updateTableUI(flightList)
                }
                if (catalogReadProblem) {
                    petSay("Список получен, но не все подписи подтверждены. Сохрани лог", true)
                } else if (records.isEmpty()) {
                    petSay("Записей полётов пока не нашёл")
                } else {
                    petSay("Полёты найдены! Записей: ${records.size}", requiresUsb = true)
                }
                if (records.isEmpty()) {
                    updateStatus("Статус: Оглавление пусто")
                    log("Оглавление пустое или не удалось распарсить записи.")
                } else {
                    updateStatus("Статус: Загружено ${records.size} включений")
                    log("Прочитано включений: ${records.size}; подписи полностью подтверждены: ${!catalogReadProblem}")
                }
            } catch (e: Exception) {
                petSay("Не удалось прочитать ЗБН. Проверь связь", true)
                log("Сбой процесса чтения оглавления", e)
                updateStatus("Статус: Сбой передачи")
            } finally {
                try { port.close() } catch (e: Exception) { log("Ошибка закрытия порта", e) }
                resetUi()
            }
        }
    }

    private fun readCatalog(
        port: UsbSerialPort,
        limit: Int,
        catalogBaudRate: Int,
        reopenPortForMetadata: () -> Unit
    ): List<FlightRecord> {
    // Команда выдачи оглавления.
    port.write(byteArrayOf(0x4D.toByte()), 1000)

    val buffer = ByteArray(16384)
    val catalogBuffer = ByteArrayOutputStream()
    var noDataCounter = 0
    var catalogComplete = true
    catalogReadProblem = false
    val catalogDeadline = System.nanoTime() + 120_000_000_000L

    while (noDataCounter < 3) {
        if (System.nanoTime() >= catalogDeadline || catalogBuffer.size() > 8 * 1024 * 1024) {
            catalogComplete = false
            catalogReadProblem = true
            log("Оглавление превысило ограничение времени или размера")
            break
        }
        try {
            val count = port.read(buffer, 5000)

            if (count > 0) {
                noDataCounter = 0
                catalogBuffer.write(buffer, 0, count)

                // The catalog is a continuous stream. Formatting every large
                // block as HEX/ASCII here delays the next USB read and can
                // overflow the FTDI receive path, dropping descriptor bytes.
                runOnUiThread {
                    tvStatus.text =
                        "Статус: Получено оглавления: ${catalogBuffer.size()} байт..."
                }
            } else {
                noDataCounter += 1
            }
        } catch (e: Exception) {
            catalogComplete = false
            catalogReadProblem = true
            runOnUiThread {
                refreshPetUsb()
                petSay("Связь с ЗБН прервалась. Проверь кабель", true)
            }
            log("Ошибка во время чтения оглавления", e)
            break
        }
    }

    val rawCatalog = catalogBuffer.toByteArray()
    val descriptorSize = ZbnTocParser.DESCRIPTOR_SIZE
    val trailingByteCount = rawCatalog.size % descriptorSize
    // A complete ZBN catalog is an aligned sequence of 16-byte slots followed
    // by one service byte. Its value differs between observed ZBN units, so the
    // alignment is authoritative; the byte itself is recorded for diagnostics.
    val hasTrailingServiceByte = trailingByteCount == 1
    val catalogBytes = if (hasTrailingServiceByte) {
        val serviceByte = rawCatalog.last().toInt() and 0xFF
        log(
            "После полного оглавления получен служебный завершающий байт " +
                "0x${serviceByte.toString(16).uppercase().padStart(2, '0')}"
        )
        rawCatalog.copyOf(rawCatalog.size - 1)
    } else {
        rawCatalog
    }

    catalogReadProblem =
        catalogReadProblem || catalogBytes.size % descriptorSize != 0
    val records = tocParser.parse(catalogBytes)

    log(
        "Вычитывание завершено. Получено байт: ${catalogBuffer.size()}. " +
            "Найдено включений: ${records.size}"
    )

    val selected = records
        .sortedByDescending { it.number }
        .take(limit.coerceIn(0, 1000))
        .toMutableList()
    log(
        "Выбраны включения: " + selected.joinToString { record ->
            "№${record.number}@0x${record.startAddress.toString(16)} банк=${record.memoryBank}"
        }
    )
    val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
    val rates = resources.getStringArray(R.array.reg_speeds)
    val formats = resources.getStringArray(R.array.arinc_types)
    val rate = rates.getOrNull(prefs.getInt("reg_speed", 0))
    val format = formats.getOrNull(prefs.getInt("arinc", 0))
    if (rate != "64" || format != "573") {
        log("Декодер подписей проверен только для ARINC-573, 64 слова/с")
        return selected.map { it.copy(duration = "—") }
    }
    if (!catalogComplete || catalogBytes.size % descriptorSize != 0) {
        log("Оглавление может быть неполным: запросы страниц не выполняются")
        return selected
    }
    val reader = ZbnMetadataReader(
        port = port,
        catalogBaudRate = catalogBaudRate,
        metadataBaudRate = 921600
    ) { message -> log(message) }
    // Keep the table ordered newest-first, but read metadata oldest-first.
    // Isolated service/anomalous record numbers sort above normal flight
    // sequences and can time out; processing them last preserves metadata for
    // the valid sequence already shown in the table.
    val metadataOrder = selected.indices.sortedBy { selected[it].number }
    readingMetadata = true
    try {
        for ((position, index) in metadataOrder.withIndex()) {
            val record = selected[index]
            updateStatus("Чтение подписей: ${position + 1}/${selected.size}, №${record.number}")
            try {
                reopenPortForMetadata()
                val metadata = reader.read(record)
                if (metadata.date == null || metadata.startTime == null ||
                    metadata.flightNum == null || metadata.tailNum == null) {
                    catalogReadProblem = true
                    log("№${record.number}: подписи не подтверждены полностью; прочерки не означают успешную расшифровку")
                }
                selected[index] = record.copy(
                    date = metadata.date ?: "—",
                    startTime = metadata.startTime ?: "—",
                    flightNum = metadata.flightNum ?: "—",
                    tailNum = metadata.tailNum ?: "—"
                )
                log("№${record.number}: дата=${metadata.date}, начало=${metadata.startTime}, " +
                    "рейс=${metadata.flightNum}, борт=${metadata.tailNum}")
            } catch (e: Exception) {
                // Stop on an uncertain transaction; do not send further commands
                // into an unfinished page response. Keep the catalog visible.
                catalogReadProblem = true
                log("Подписи №${record.number} не получены. Чтение страниц остановлено", e)
                break
            }
        }
    } finally {
        readingMetadata = false
    }
    return selected
}

    private fun rememberLastTailNumber(records: List<FlightRecord>) {
        val tail = records.asSequence()
            .map { DocumentFileName.normalizeTailNumber(it.tailNum) }
            .firstOrNull { it.isNotEmpty() }
            ?: return
        getSharedPreferences("AppSettings", MODE_PRIVATE)
            .edit()
            .putString(DocumentFileName.PREF_LAST_TAIL, tail)
            .apply()
        refreshHeaderTail()
    }

    private fun refreshHeaderTail() {
        val tail = DocumentFileName.normalizeTailNumber(
            getSharedPreferences("AppSettings", MODE_PRIVATE)
                .getString(DocumentFileName.PREF_LAST_TAIL, "").orEmpty()
        )
        headerTailValue.text = if (tail.isEmpty()) "RA—БОРТ НЕ ОПРЕДЕЛЁН" else "RA-$tail"
    }

    private fun tableCellDrawable(fillColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        setStroke(dp(1), Color.parseColor("#2B3540"))
        cornerRadius = 0f
    }

    private fun pageTab(label: String, selected: Boolean) = TextView(this).apply {
        text = label
        textSize = 11.5f
        typeface = resources.getFont(R.font.zbn_sans_bold)
        gravity = Gravity.CENTER
        minHeight = dp(34)
        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }
        stylePageTab(this, selected)
    }

    private fun stylePageTab(tab: TextView, selected: Boolean) {
        tab.setTextColor(if (selected) COLOR_ACCENT_TEXT else COLOR_TEXT_MUTED)
        tab.background = if (selected) {
            createRoundedDrawable(COLOR_ACCENT, 14f)
        } else {
            createRoundedDrawable(Color.TRANSPARENT, 14f)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class StaticPagesAdapter(private val pages: List<View>) :
        RecyclerView.Adapter<StaticPagesAdapter.PageHolder>() {
        class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageHolder =
            PageHolder(FrameLayout(parent.context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            })

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            (pages[position].parent as? android.view.ViewGroup)?.removeView(pages[position])
            holder.container.removeAllViews()
            holder.container.addView(pages[position], FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        override fun getItemCount(): Int = pages.size
    }
    private fun copySelectedFlight() {
        if (!flightCopyVerified) {
            petSay("Скачивание пока недоступно: протокол ещё проверяется", true)
            log("Копирование заблокировано до проверки протокола сохранения")
            return
        }
        val record = selectedRecord ?: return
        require(record.startAddress >= 0 && record.endAddress >= record.startAddress)
        require(record.sizeBytes > 0 && record.sizeBytes % 512L == 0L)
        mi171Pet.setBusy(true)
        val bytesToRead = record.sizeBytes
        setCustomButtonState(btnCopySelected, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
        tvStatus.text = "Статус: Скачивание №${record.number} (0%)..."
        log(
            "Начало безопасного постраничного чтения №${record.number}: " +
                "адреса=0x${record.startAddress.toString(16)}.." +
                "0x${record.endAddress.toString(16)}, размер=$bytesToRead"
        )
        lifecycleScope.launch(Dispatchers.IO) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = findUsbDriver(usbManager)
            if (driver == null) {
                petSay("Не вижу USB. Проверь кабель", true)
                log("Ошибка скачивания: Конвертер USB не найден")
                errorRecordNumbers.add(record.number)
                runOnUiThread { updateTableUI(flightList) }
                resetUi()
                return@launch
            }
            var connection = usbManager.openDevice(driver.device) ?: run {
                petSay("Нет доступа к USB. Разреши подключение", true)
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
                val baud = prefs.getInt("baud_rate", 115200)
                port.open(connection)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = false
                port.rts = false
                petSay("Скачиваю полёт №${record.number}")
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
                var writtenBytes = 0L
                val pageAddresses = generateSequence(record.startAddress) { previous ->
                    (previous + 32).takeIf { it <= record.endAddress }
                }.toList()
                FileOutputStream(outputFile).use { fos ->
                    for ((pageIndex, pageAddress) in pageAddresses.withIndex()) {
                        if (pageIndex > 0) {
                            try { port.close() } catch (_: Exception) {}
                            connection.close()
                            connection = usbManager.openDevice(driver.device)
                                ?: throw IllegalStateException("Нет доступа к USB при чтении страницы")
                            port.open(connection)
                            port.setParameters(
                                baud, 8,
                                UsbSerialPort.STOPBITS_1,
                                UsbSerialPort.PARITY_NONE
                            )
                            port.dtr = false
                            port.rts = false
                        }
                        val reader = ZbnMetadataReader(
                            port = port,
                            catalogBaudRate = baud,
                            metadataBaudRate = 921600
                        ) { message -> log("COPY: $message") }
                        val rawPage = reader.readRawPage(record, pageAddress)
                        val payload = ZbnMetadataReader.extractRecordPayload(
                            rawPage,
                            record.number,
                            pageAddress
                        )
                        if (writtenBytes + payload.size > bytesToRead) {
                            throw IllegalStateException("Получено больше данных, чем указано в оглавлении")
                        }
                        fos.write(payload)
                        writtenBytes += payload.size
                        val percent = ((writtenBytes * 100) / bytesToRead).toInt().coerceAtMost(100)
                        runOnUiThread {
                            progressBar.progress = percent
                            tvStatus.text = "Статус: Скачивание №${record.number}... $percent%"
                        }
                        log(
                            "COPY_PAGE №${record.number}: ${pageIndex + 1}/${pageAddresses.size}, " +
                                "адрес=0x${pageAddress.toString(16)}, данных=${payload.size}, " +
                                "итого=$writtenBytes/$bytesToRead"
                        )
                    }
                }
                if (writtenBytes != bytesToRead) {
                    petSay("Полёт №${record.number} получен не полностью. Проверь связь", true)
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
                    petSay("Полёт №${record.number} скачан")
                    downloadedRecordNumbers.add(record.number)
                    errorRecordNumbers.remove(record.number)
                    log("Успешно сохранен рейс №${record.flightNum} ($writtenBytes байт)")
                    updateStatus("Статус: Сохранен рейс №${record.flightNum}")
                }
                runOnUiThread { updateTableUI(flightList) }
            } catch (e: Exception) {
                petSay("Не удалось скачать полёт №${record.number}. Проверь связь и память", true)
                log("Исключение при скачивании включения №${record.number}", e)
                errorRecordNumbers.add(record.number)
                updateStatus("Статус: Ошибка сбоя связи")
                outputFile?.let { if (it.exists()) it.delete() }
                runOnUiThread { updateTableUI(flightList) }
            } finally {
                try { port.close() } catch (e: Exception) { log("Ошибка закрытия порта", e) }
                connection.close()
                resetUi()
            }
        }
    }

    private fun executeFullDumpCommand() {
        if (!fullDumpVerified) {
            log("Полный дамп заблокирован: старая команда читает только оглавление")
            return
        }
        mi171Pet.setBusy(true)
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
                petSay("Не вижу USB. Проверь адаптер и кабель", true)
                updateStatus("Статус: Ошибка (Нет адаптера)")
                resetUi()
                return@launch
            }
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                log("Ошибка: Нет доступа к USB при дампе")
                petSay("Нет доступа к USB. Разреши подключение", true)
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
        port.write(byteArrayOf(0x05), 1000)
        val ack = ByteArray(1024)
        val readAck = port.read(ack, 1000)
        if (readAck <= 0 || ack[0] != 0x06.toByte()) {
            log("Ошибка дампа: ЗБН не ответил (ACK не получен)")
            petSay("ЗБН не отвечает. Проверь питание и подключение", true)
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
        runOnUiThread {
            tvStatus.text = text
            when {
                text.contains("Ошибка", ignoreCase = true) ||
                    text.contains("Сбой", ignoreCase = true) -> mi171Pet.play("failed", repeat = false)
                text.contains("Загружено", ignoreCase = true) -> mi171Pet.play("review", repeat = false)
            }
        }
    }

    private fun resetUi() {
        runOnUiThread {
            mi171Pet.setBusy(false)
            setCustomButtonState(btnStart, true, COLOR_ACCENT, COLOR_ACCENT_TEXT, 24f)
            setCustomButtonState(btnFullDump, false, COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
            setCustomButtonState(
                btnCopySelected,
                flightCopyVerified && selectedRecord != null,
                COLOR_SURFACE_CONTAINER,
                COLOR_TEXT,
                16f
            )
            setCustomButtonState(btnExportExcel, flightList.isNotEmpty(), COLOR_SURFACE_CONTAINER, COLOR_TEXT, 16f)
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            if (!tvStatus.text.contains("Ошибка", ignoreCase = true) &&
                !tvStatus.text.contains("Сбой", ignoreCase = true) &&
                !tvStatus.text.contains("Загружено", ignoreCase = true)) {
                mi171Pet.play("idle")
            }
        }
    }
}
