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
            // Структура 16-байтового кадра ЗБН:
            // frame[0..1] = 0x55 0xAA (Маркер начала кадра)
            // frame[2..3] = Время / Контрольные данные
            // frame[4..5] = Дата / Рейс
            // frame[6]    = Номер включения (счётчик, напр. 0x46, 0x47...)
            // frame[7]    = Флаг состояния
            // frame[8..15]= Смещение адреса памяти и бортовой номер

            val rawInclusionNum = frame[6].toInt() and 0xFF
            val flightNumVal = if (rawInclusionNum in 32..126) {
                "${rawInclusionNum.toChar()} ($rawInclusionNum)"
            } else {
                "$rawInclusionNum"
            }

            // Извлекаем адрес или размер включения из байт [8..11]
            val startAddress = parseUInt32LE(frame, 8)

            // Разбор даты и времени
            val b2 = frame[2].toInt() and 0xFF
            val b3 = frame[3].toInt() and 0xFF
            val b4 = frame[4].toInt() and 0xFF
            val b5 = frame[5].toInt() and 0xFF

            val dateFormatted = String.format("%02d.%02d.20%02d", bcdToDec(b4), bcdToDec(b5), 26)
            val startTimeFormatted = String.format("%02d:%02d:%02d", bcdToDec(b2), bcdToDec(b3), 0)
            val tailNumVal = String.format("%02X%02X", frame[14].toInt() and 0xFF, frame[15].toInt() and 0xFF)

            return FlightRecord(
                number = sectorIndex,
                sizeBytes = if (startAddress > 0) startAddress else (sectorIndex * 1024L),
                date = dateFormatted,
                duration = "--:--",
                startTime = startTimeFormatted,
                endTime = "-",
                flightNum = flightNumVal,
                tailNum = tailNumVal
            )
        } catch (e: Exception) {
            logDebug("Ошибка разбора отдельного кадра: ${e.message}")
            return null
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
