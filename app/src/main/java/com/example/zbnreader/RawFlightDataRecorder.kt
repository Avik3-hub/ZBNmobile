package com.example.zbnreader

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
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
     * @param performHandshake Если true, отправляет 0x05 и 0x4D для запускa передачи из ЗБН.
     */
    suspend fun startRecording(
        baudRate: Int = 115200,
        performHandshake: Boolean = true,
        onBytesRecorded: (Long) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // 1. Поиск подключенного USB-RS422 адаптера
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
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
        try {
            usbPort = driver.ports[0].apply {
                open(connection)
                setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                
                // Настройка сигналов управления для RS-422
                dtr = false
                rts = false
            }
        } catch (e: Exception) {
            onError("Ошибка открытия порта: ${e.localizedMessage}")
            return@withContext
        }

        // 3. Инициализация передачи, если требуется
        if (performHandshake) {
            try {
                val port = usbPort ?: return@withContext
                port.write(byteArrayOf(0x05), 1000)
                val ack = ByteArray(1)
                val bytesRead = port.read(ack, 1000)
                if (bytesRead <= 0 || ack[0] != 0x06.toByte()) {
                    onError("Ошибка связи: Накопитель не ответил на рукопожатие (ACK)")
                    closePort()
                    return@withContext
                }
                // Запуск непрерывного потока
                port.write(byteArrayOf(0x4D.toByte()), 1000)
            } catch (e: Exception) {
                onError("Ошибка рукопожатия: ${e.localizedMessage}")
                closePort()
                return@withContext
            }
        }

        // 4. Подготовка файла для записи сырых байт
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FLIGHT_LOG_$timeStamp.bin"
        val outputDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        val outputFile = File(outputDirectory, fileName)

        isRecording.set(true)
        var totalBytesWritten = 0L
        var lastCallbackTime = 0L

        try {
            // Буферизованный поток записи на диск
            BufferedOutputStream(FileOutputStream(outputFile, true)).use { bufferedOutput ->
                val buffer = ByteArray(8192)

                while (isRecording.get()) {
                    val bytesRead = usbPort?.read(buffer, 200) ?: 0

                    if (bytesRead > 0) {
                        bufferedOutput.write(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead

                        // Ограничение частоты обновления UI (не чаще 5 раз в секунду)
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastCallbackTime > 200) {
                            onBytesRecorded(totalBytesWritten)
                            lastCallbackTime = currentTime
                        }
                    }
                }
                bufferedOutput.flush()
            }
            onBytesRecorded(totalBytesWritten)
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
