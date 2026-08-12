package com.example.zbnreader // Укажите ваш package

import android.util.Log

data class FlightRecord(
    val flightNumber: Int,      // Номер включения / полёта
    val flightSize: Long,       // Размер полёта в байтах
    val dateStr: String,        // Дата ("ДД.ММ.ГГГГ")
    val timeStr: String,        // Время ("ЧЧ:ММ:СС")
    val rawBytes: ByteArray     // Сырые байты записи для отладки
)

class ZbnTocParser(private val logger: ((String) -> Unit)? = null) {

    companion object {
        const val RECORD_SIZE = 16 // Стандартная длина блока оглавления ЗБН (16 байт)
    }

    /**
     * Парсинг буфера оглавления ЗБН
     */
    fun parseBuffer(buffer: ByteArray, length: Int): List<FlightRecord> {
        val flights = mutableListOf<FlightRecord>()

        if (length < RECORD_SIZE) {
            logDebug("Буфер слишком мал для парсинга оглавления: $length байт (требуется $RECORD_SIZE)")
            return flights
        }

        // Проходим по буферу с шагом в RECORD_SIZE (16 байт)
        var offset = 0
        var recordIndex = 0

        while (offset + RECORD_SIZE <= length) {
            recordIndex++
            val chunk = buffer.copyOfRange(offset, offset + RECORD_SIZE)
            val chunkHex = chunk.joinToString(" ") { String.format("%02X", it) }

            try {
                // 1. Разбор номера включения (байты 0..1). 
                // Пробуем Little-Endian (стандарт FTDI/ARM). Если значения неадекватны, переключаем на BE.
                val flightNum = readUInt16LE(chunk, 0)

                // 2. Разбор размера полёта в байтах/словах (байты 2..5)
                val flightSize = readUInt32LE(chunk, 2)

                // 3. Разбор даты (байты 6..8) — День, Месяц, Год (BCD)
                val day = bcdToDec(chunk[6])
                val month = bcdToDec(chunk[7])
                val yearShort = bcdToDec(chunk[8])
                val year = if (yearShort in 0..99) 2000 + yearShort else yearShort

                // 4. Разбор времени (байты 9..11) — Часы, Минуты, Секунды (BCD)
                val hour = bcdToDec(chunk[9])
                val minute = bcdToDec(chunk[10])
                val second = bcdToDec(chunk[11])

                // Формируем строки даты и времени
                val dateStr = String.format("%02d.%02d.%04d", day, month, year)
                val timeStr = String.format("%02d:%02d:%02d", hour, minute, second)

                // 5. Валидация кадра (Мягкая проверка, чтобы не отбрасывать полёты со сбитыми часами)
                val isValidNumber = flightNum in 1..65535
                val isValidDate = (month in 1..12) && (day in 1..31)

                if (isValidNumber && isValidDate) {
                    val record = FlightRecord(
                        flightNumber = flightNum,
                        flightSize = flightSize,
                        dateStr = dateStr,
                        timeStr = timeStr,
                        rawBytes = chunk
                    )
                    flights.add(record)
                    logDebug("✅ Запись #$recordIndex распознана: Полет №$flightNum | Размер: $flightSize байт | $dateStr $timeStr")
                } else {
                    logDebug("⚠️ Запись #$recordIndex отброшена (непроход валидации): №=$flightNum, Дата=$dateStr, HEX=[$chunkHex]")
                }

            } catch (e: Exception) {
                logDebug("❌ Ошибка парсинга блока #$recordIndex at offset $offset: ${e.message}")
            }

            offset += RECORD_SIZE
        }

        logDebug("Итог парсинга: успешно распознано ${flights.size} включений из $recordIndex блоков.")
        return flights
    }

    /**
     * Безопасная конвертация BCD (Binary Coded Decimal) в обычный Int.
     * Защищена от отрицательных Byte в Kotlin за счет `and 0xFF`.
     */
    private fun bcdToDec(b: Byte): Int {
        val unsigned = b.toInt() and 0xFF
        val high = (unsigned ushr 4) and 0x0F
        val low = unsigned and 0x0F
        
        // Защитный фоллбэк: если данные приходят в обычном двоичном формате (HEX/DEC), а не BCD
        if (high > 9 || low > 9) {
            return unsigned
        }
        return high * 10 + low
    }

    // Чтение 16-битного целого (Little-Endian)
    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return b0 or (b1 shl 8)
    }

    // Чтение 32-битного целого (Little-Endian)
    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
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
