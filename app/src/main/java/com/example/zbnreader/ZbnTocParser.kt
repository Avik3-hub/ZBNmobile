package com.example.zbnreader

import java.util.Locale

class ZbnTocParser(
    private val logger: ((String) -> Unit)? = null
) {

    companion object {
        private const val FRAME_SIZE = 16
        private const val SYNC_BYTE_1 = 0x55.toByte()
        private const val SYNC_BYTE_2 = 0xAA.toByte()
    }

    fun parseBuffer(buffer: ByteArray, bytesRead: Int): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        var i = 0

        while (i <= bytesRead - FRAME_SIZE) {
            // Проверяем маркер 0x55 0xAA на байтах 12 и 13 текущего кадра
            if (buffer[i + 12] == SYNC_BYTE_1 && buffer[i + 13] == SYNC_BYTE_2) {
                val record = parseFrameToFlightRecord(buffer, i)

                if (record != null) {
                    records.add(record)
                    i += FRAME_SIZE // Переходим к следующему кадру
                    continue
                } else {
                    logDebug("Кадр на смещении $i имеет неверную CRC16 и был пропущен.")
                }
            }
            i++
        }

        return records
    }

    private fun parseFrameToFlightRecord(buffer: ByteArray, offset: Int): FlightRecord? {
        // Проверка CRC16 (байты 14-15, Little-Endian)
        val expectedCrc = ((buffer[offset + 15].toInt() and 0xFF) shl 8) or (buffer[offset + 14].toInt() and 0xFF)
        val calculatedCrc = calculateCrc16(buffer, offset, 14)

        if (expectedCrc != calculatedCrc) {
            logDebug(String.format("Сбой CRC16! Ожидалось: 0x%04X, Рассчитано: 0x%04X", expectedCrc, calculatedCrc))
            return null
        }

        // Байт 0: Индекс сектора / № включения
        val sectorIndex = buffer[offset + 0].toInt() and 0xFF

        // Байт 1: Статусный байт / Бортовой номер
        val tailNumRaw = buffer[offset + 1].toInt() and 0xFF
        val tailNumVal = String.format(Locale.US, "%03d", tailNumRaw)

        // Байты 2..4: 24-битный адрес смещения в памяти (Little-Endian)
        val addrLsb = buffer[offset + 2].toInt() and 0xFF
        val addrMid = buffer[offset + 3].toInt() and 0xFF
        val addrMsb = buffer[offset + 4].toInt() and 0xFF
        val startAddress = (addrMsb shl 16) or (addrMid shl 8) or addrLsb

        // --- РАЗБОР МЕТАДАННЫХ (Байты 5..11) ---
        val day = bcdToInt(buffer[offset + 5])
        val hour = bcdToInt(buffer[offset + 6])
        val min = bcdToInt(buffer[offset + 7])
        val sec = bcdToInt(buffer[offset + 8])
        val month = bcdToInt(buffer[offset + 9])
        
        // Байт 10: Год в формате BCD (например, 0x26 -> 26)
        val parsedYear = bcdToInt(buffer[offset + 10])
        val year = if (parsedYear in 0..99) parsedYear else 26

        val startTimeFormatted = String.format(
            Locale.US,
            "%02d:%02d:%02d",
            hour.coerceIn(0, 23),
            min.coerceIn(0, 59),
            sec.coerceIn(0, 59)
        )
        val dateFormatted = String.format(
            Locale.US,
            "%02d.%02d.%02d",
            day.coerceIn(1, 31),
            month.coerceIn(1, 12),
            year
        )

        // Байт 11: Номер рейса
        val flightNumVal = (buffer[offset + 11].toInt() and 0xFF).toString()

        return FlightRecord(
            number = sectorIndex,
            sizeBytes = startAddress.toLong(),
            date = dateFormatted,
            duration = "--:--",
            startTime = startTimeFormatted,
            endTime = "-",
            flightNum = flightNumVal,
            tailNum = tailNumVal
        )
    }

    /**
     * Преобразование BCD (Binary-Coded Decimal) в обычный Int.
     */
    private fun bcdToInt(b: Byte): Int {
        val byteVal = b.toInt() and 0xFF
        val high = byteVal ushr 4
        val low = byteVal and 0x0F
        return if (high <= 9 && low <= 9) {
            high * 10 + low
        } else {
            byteVal
        }
    }

    private fun calculateCrc16(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (j in offset until (offset + length)) {
            crc = crc xor (bytes[j].toInt() and 0xFF)
            for (k in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun logDebug(message: String) {
        logger?.invoke("[ZbnTocParser] $message")
    }
}
