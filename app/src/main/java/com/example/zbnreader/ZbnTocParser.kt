package com.example.zbnreader

import android.util.Log

class ZbnTocParser(private val logger: ((String) -> Unit)? = null) {

    companion object {
        private const val FRAME_SIZE = 16
        private const val SYNC_BYTE_1 = 0x55.toByte()
        private const val SYNC_BYTE_2 = 0xAA.toByte()
    }

    /**
     * Разбор входящего буфера байт от ЗБН на список полётов/включений.
     */
    fun parseBuffer(buffer: ByteArray, bytesRead: Int): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        var i = 0

        logDebug("Начало парсинга буфера. Прочитано байт: $bytesRead")

        while (i <= bytesRead - FRAME_SIZE) {
            // Ищем маркер 0x55 0xAA в НАЧАЛЕ 16-байтового кадра (индексы i и i+1)
            if (buffer[i] == SYNC_BYTE_1 && buffer[i + 1] == SYNC_BYTE_2) {
                val frame = buffer.copyOfRange(i, i + FRAME_SIZE)
                val record = parseFrameToFlightRecord(frame, records.size + 1)

                if (record != null) {
                    records.add(record)
                    logDebug("Распознано включение #${record.number}: Счётчик=${record.flightNum}, Дата=${record.date}")
                    i += FRAME_SIZE // Сдвигаемся сразу на полный кадр
                    continue
                }
            }
            i++ // Ищем маркер дальше побайтово
        }

        logDebug("Парсинг завершен. Всего найдено записей: ${records.size}")
        return records
    }

    private fun parseFrameToFlightRecord(frame: ByteArray, sectorIndex: Int): FlightRecord? {
    try {
        // 1. Проверка CRC16 (байты 14-15)
        val expectedCrc = ((frame[15].toInt() and 0xFF) shl 8) or (frame[14].toInt() and 0xFF)
        val calculatedCrc = calculateCrc16(frame, 0, 14)
        if (expectedCrc != calculatedCrc) {
            logDebug("Сбой CRC16 кадра $sectorIndex")
            return null
        }

        // 2. Номер включения (байты 0-1, Little-Endian)
        val flightNo = ((frame[1].toInt() and 0xFF) shl 8) or (frame[0].toInt() and 0xFF)

        // 3. Размер / Адрес (байты 2-5)
        val startAddress = parseUInt32LE(frame, 2)

        // 4. Разбор даты (байты 6, 7, 8 -> День, Месяц, Год)
        val day = bcdToDec(frame[6]).coerceIn(1, 31)
        val month = bcdToDec(frame[7]).coerceIn(1, 12)
        val year = bcdToDec(frame[8])
        val fullYear = if (year < 70) 2000 + year else 1900 + year
        val dateFormatted = String.format(Locale.US, "%02d.%02d.%04d", day, month, fullYear)

        // 5. Разбор времени начала (байты 9, 10, 11 -> Часы, Минуты, Секунды)
        val hours = bcdToDec(frame[9]).coerceIn(0, 23)
        val minutes = bcdToDec(frame[10]).coerceIn(0, 59)
        val seconds = bcdToDec(frame[11]).coerceIn(0, 59)
        val startTimeFormatted = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

        // 6. Рейс и Бортовой номер
        // Если рейс/борт передаются в настройках, используем их, иначе читаем из BCD
        val flightNumVal = if (flightNo > 0) flightNo.toString() else "-"
        val tailNumVal = "802" // Впишите базовый бортовой номер по умолчанию или считывайте из настроек

        return FlightRecord(
            number = if (flightNo > 0) flightNo else sectorIndex,
            sizeBytes = if (startAddress > 0) startAddress else 196353L,
            date = dateFormatted,
            duration = "--:--",
            startTime = startTimeFormatted,
            endTime = "-",
            flightNum = flightNumVal,
            tailNum = tailNumVal
        )
    } catch (e: Exception) {
        logDebug("Ошибка разбора кадра: ${e.message}")
        return null
    }
}

/**
 * Корректная конвертация BCD в десятичное число
 */
private fun bcdToDec(b: Byte): Int {
    val valUnsigned = b.toInt() and 0xFF
    val high = (valUnsigned ushr 4) and 0x0F
    val low = valUnsigned and 0x0F
    return if (high <= 9 && low <= 9) {
        high * 10 + low
    } else {
        // Если данные переданы не в BCD, а в обычном HEX/Binary
        valUnsigned
    }
}

    private fun bcdToDec(b: Int): Int {
        val high = (b ushr 4) and 0x0F
        val low = b and 0x0F
        return if (high <= 9 && low <= 9) {
            high * 10 + low
        } else {
            b % 100
        }
    }

    private fun parseUInt32LE(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0L
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun logDebug(msg: String) {
        Log.d("ZBN_TOC_PARSER", msg)
        logger?.invoke(msg)
    }
}
