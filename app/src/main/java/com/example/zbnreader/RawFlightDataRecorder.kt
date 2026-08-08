package com.example.zbnreader

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProbes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RawFlightDataRecorder(private val context: Context) {

    private val isRecording = AtomicBoolean(false)
    private var usbPort: UsbSerialPort? = null

    /**
     * Запуск чтения RS-422 и прямого сохранения бинарного потока в файл.
     */
    suspend fun startRecording(
        baudRate: Int = 115200,
        onBytesRecorded: (Long) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // 1. Поиск подключенного USB-RS422 адаптера
        val availableDrivers = UsbSerialProbes.getDefaultProbeTable().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            onError("USB-RS422 адаптер не обнаружен")
            return@withContext
        }

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device)
            ?: run {
                onError("Нет разрешения на доступ к USB-устройству")
                return@withContext
            }

        // 2. Открытие и настройка COM-порта
        usbPort = driver.ports[0].apply {
            open(connection)
            setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }

        // 3. Подготовка файла для записи сырых байт
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FLIGHT_LOG_$timeStamp.bin"
        val outputDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        val outputFile = File(outputDirectory, fileName)

        isRecording.set(true)
        var totalBytesWritten = 0L

        try {
            // Поток прямой записи байтов на диск
            FileOutputStream(outputFile, true).use { fileOutputStream ->
                val buffer = ByteArray(8192) // Буфер приема на 8 КБ

                while (isRecording.get()) {
                    val bytesRead = usbPort?.read(buffer, 200) ?: 0

                    if (bytesRead > 0) {
                        // Запись чистых байтов без текстовых конвертаций
                        fileOutputStream.write(buffer, 0, bytesRead)
                        fileOutputStream.flush()

                        totalBytesWritten += bytesRead
                        onBytesRecorded(totalBytesWritten)
                    }
                }
            }
        } catch (e: Exception) {
            onError("Ошибка записи: ${e.localizedMessage}")
        } finally {
            closePort()
        }
    }

    fun stopRecording() {
        isRecording.set(false)
    }

    private fun closePort() {
        try {
            usbPort?.close()
        } catch (_: Exception) {}
        usbPort = null
    }
}

