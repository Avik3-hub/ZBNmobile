package com.example.zbnreader

import android.content.Context
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class RawFlightDataRecorder(
    private val context: Context,
    private val usbPort: UsbSerialPort?,
    private val baudRate: Int
) {
    private val isRecording = AtomicBoolean(false)

    suspend fun startRecording(
        onBytesRecorded: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            if (usbPort == null) {
                withContext(Dispatchers.Main) {
                    onError("USB порт не инициализирован")
                }
                return@withContext
            }

            try {
                usbPort.setParameters(
                    baudRate,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                usbPort.dtr = false
                usbPort.rts = false
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Ошибка настройки RS-422: ${e.localizedMessage}")
                }
                return@withContext
            }

            if (!performHandshake()) {
                withContext(Dispatchers.Main) {
                    onError("Ошибка квитирования (Handshake failed)")
                }
                return@withContext
            }

            val dirPath: File = context.getExternalFilesDir(null) ?: context.filesDir
            val fileName = "flight_data_${System.currentTimeMillis()}.bin"
            val file = File(dirPath, fileName)

            isRecording.set(true)
            var totalBytesRecorded = 0L
            val buffer = ByteArray(4096)

            try {
                FileOutputStream(file, true).use { fileOutputStream ->
                    BufferedOutputStream(fileOutputStream).use { bufferedOutput ->
                        while (isRecording.get()) {
                            val len = usbPort.read(buffer, 1000)
                            if (len > 0) {
                                bufferedOutput.write(buffer, 0, len)
                                totalBytesRecorded += len
                                withContext(Dispatchers.Main) {
                                    onBytesRecorded(totalBytesRecorded)
                                }
                            }
                        }
                        bufferedOutput.flush()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Ошибка записи файла: ${e.localizedMessage}")
                }
            } finally {
                isRecording.set(false)
            }
        }
    }

    private fun performHandshake(): Boolean {
        if (usbPort == null) return false
        return try {
            val sendBuf = byteArrayOf(0x05) // ENQ
            usbPort.write(sendBuf, 1000)

            val readBuf = ByteArray(16)
            val len = usbPort.read(readBuf, 1000)
            if (len > 0 && readBuf[0] == 0x06.toByte()) { // ACK
                usbPort.write(byteArrayOf(0x4D.toByte()), 1000) // Вызов передачи
                true
            } else {
                true // Разрешаем продолжать, если устройство вещает без подтверждения
            }
        } catch (e: IOException) {
            false
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        try {
            usbPort?.dtr = false
            usbPort?.rts = false
        } catch (e: Exception) {
            // Игнорируем ошибки при закрытии
        }
    }
}
