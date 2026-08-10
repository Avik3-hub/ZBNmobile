package com.example.zbnreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FlightDownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private const val CHANNEL_ID = "zbn_download_channel"
        private const val NOTIF_ID = 1001

        const val ACTION_START_COPY = "ACTION_START_COPY"
        const val EXTRA_RECORD_NUMBER = "EXTRA_RECORD_NUMBER"
        const val EXTRA_RECORD_DATE = "EXTRA_RECORD_DATE"
        const val EXTRA_RECORD_FLIGHT = "EXTRA_RECORD_FLIGHT"
        const val EXTRA_START_OFFSET = "EXTRA_START_OFFSET"
        const val EXTRA_BYTES_TO_READ = "EXTRA_BYTES_TO_READ"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_COPY) {
            val recordNum = intent.getIntExtra(EXTRA_RECORD_NUMBER, 0)
            val recordDate = intent.getStringExtra(EXTRA_RECORD_DATE) ?: ""
            val flightNum = intent.getStringExtra(EXTRA_RECORD_FLIGHT) ?: ""
            val startOffset = intent.getLongExtra(EXTRA_START_OFFSET, 0L)
            val bytesToRead = if (intent.hasExtra(EXTRA_BYTES_TO_READ)) {
                intent.getLongExtra(EXTRA_BYTES_TO_READ, -1L)
            } else null

            startForeground(NOTIF_ID, createNotification("Подготовка к скачиванию..."))

            serviceScope.launch {
                executeDownloadProcess(recordNum, recordDate, flightNum, startOffset, bytesToRead)
            }
        }
        return START_NOT_STICKY
    }

    private fun executeDownloadProcess(
        recordNum: Int,
        recordDate: String,
        flightNum: String,
        startOffset: Long,
        bytesToRead: Long?
    ) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        if (drivers.isEmpty()) {
            stopWithNotification("Ошибка: USB-конвертер не найден")
            return
        }

        val driver = drivers[0]
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            stopWithNotification("Ошибка доступа к USB")
            return
        }

        val port = driver.ports[0]
        try {
            val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
            val baud = prefs.getInt("baud_rate", 921600)
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // 1. Явный сброс сигналов управления для RS-422
            port.dtr = false
            port.rts = false

            // 2. Рукопожатие с проверкой ответа ACK (0x06)
            port.write(byteArrayOf(0x05), 1000)
            val ack = ByteArray(1)
            val ackRead = port.read(ack, 1000)
            if (ackRead <= 0 || ack[0] != 0x06.toByte()) {
                stopWithNotification("Ошибка связи: Накопитель не отвечает (ACK != 0x06)")
                return
            }

            // 3. Старт непрерывного потока
            port.write(byteArrayOf(0x4D.toByte()), 1000)

            // Формирование имени выходного файла
            val dateFormatted = try {
                val inputFormat = SimpleDateFormat("dd.MM.yy", Locale.US)
                val parsedDate = inputFormat.parse(recordDate.trim())
                SimpleDateFormat("yyyyMMdd", Locale.US).format(parsedDate ?: Date())
            } catch (e: Exception) {
                SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            }

            val fileName = "${dateFormatted}_${recordNum}_НАГИБИН"
            val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val outputFile = File(downloadsDir, fileName)
            val fos = FileOutputStream(outputFile)

            val buffer = ByteArray(512)
            var skippedBytes = 0L
            var writtenBytes = 0L
            var noDataCounter = 0
            var lastNotifTime = 0L

            updateNotification("Скачивание №$recordNum: 0 Б")

            // 4. Цикл с контролем отмены и безопасной частотой обновлений UI
            while (kotlinx.coroutines.isActive) {
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
                            } else validLength

                            fos.write(buffer, validDataStart, bytesToWrite)
                            writtenBytes += bytesToWrite
                        }
                    } else {
                        val bytesToWrite = if (bytesToRead != null && (writtenBytes + count) > bytesToRead) {
                            (bytesToRead - writtenBytes).toInt()
                        } else count

                        fos.write(buffer, 0, bytesToWrite)
                        writtenBytes += bytesToWrite
                    }

                    // Обновление шторки не чаще 1 раза в секунду
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastNotifTime > 1000) {
                        val kb = writtenBytes / 1024
                        updateNotification("Скачивание №$recordNum: $writtenBytes Б ($kb КБ)")
                        lastNotifTime = currentTime
                    }

                    if (bytesToRead != null && writtenBytes >= bytesToRead) break
                } else {
                    noDataCounter++
                    if (noDataCounter >= 3) break
                }
            }

            fos.flush()
            fos.close()

            stopWithNotification("Готово! Сохранено включение №$recordNum ($writtenBytes Б)")

        } catch (e: Exception) {
            stopWithNotification("Ошибка передачи: ${e.message}")
        } finally {
            try { port.close() } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновое скачивание ЗБН",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ЗБН Reader")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, createNotification(contentText))
    }

    private fun stopWithNotification(finalText: String) {
        stopForeground(STOP_FOREGROUND_DETACH)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val finalNotif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ЗБН Reader")
            .setContentText(finalText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIF_ID + 1, finalNotif)
        stopSelf()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
