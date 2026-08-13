package com.example.zbnreader

import android.util.Log

class ZbnTocParser(private val logger: ((String) -> Unit)? = null) {

    companion object {
        // Кадр оглавления ЗБН состоит из 17 байт:
        // [0..1]   Номер включения (2 байта)
        // [2..5]   Размер записи (4 байта)
        // [6..8]   Дата: Год, Месяц, День (3 байта BCD)
        // [9..11]  Время: Часы, Минуты, Секунды (3 байта BCD)
        // [12..13] Рейс (2 байта BCD)
        // [14..16] Бортовой номер (3 байта BCD)
        private const val FRAME_SIZE = 17
    }

    fun parseBuffer(buffer: ByteArray, bytesRead: Int): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        var i = 0

        // Нарезаем входящий буфер скользящим окном
        while (i <= bytesRead - FRAME_SIZE) {
            val frame = buffer.copyOfRange(i, i + FRAME_SIZE)
            val record = parseFrameToFlightRecord(frame)

            if (record != null) {
                records.add(record)
                i += FRAME_SIZE // Кадр успешно разобран — шагаем на 17 байт вперед
            } else {
                i++ // Если кадр битый или со смещением — сдвигаемся на 1 байт для поиска синхронизации
            }
        }

        return records
    }

    fun parseFrameToFlightRecord(frame: ByteArray): FlightRecord? {
        if (frame.size < FRAME_SIZE) return null

        // 1. Номер включения (2 байта Big-Endian)
        val flightNum = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)

        // Фильтр стёртых/невалидных сессий (0x0000 или 0xFFFF)
        if (flightNum == 0 || flightNum == 0xFFFF) return null

        // 2. Размер записи (4 байта Big-Endian)
        val size = ((frame[2].toLong() and 0xFF) shl 24) or
                   ((frame[3].toLong() and 0xFF) shl 16) or
                   ((frame[4].toLong() and 0xFF) shl 8) or
                   (frame[5].toLong() and 0xFF)

        // 3. Дата (3 байта BCD: [Год, Месяц, День])
        val year = bcdToInt(frame[6])
        val month = bcdToInt(frame[7])
        val day = bcdToInt(frame[8])

        // Валидация диапазонов даты
        if (year < 0 || month !in 1..12 || day !in 1..31) return null
        val dateStr = String.format("%02d.%02d.20%02d", day, month, year)

        // 4. Время (3 байта BCD: [Часы, Минуты, Секунды])
        val hour = bcdToInt(frame[9])
        val min = bcdToInt(frame[10])
        val sec = bcdToInt(frame[11])

        // Валидация диапазонов времени
        if (hour !in 0..23 || min !in 0..59 || sec !in 0..59) return null
        val timeStr = String.format("%02d:%02d:%02d", hour, min, sec)

        // 5. Рейс (2 байта BCD)
        val flightName = bcdToString(byteArrayOf(frame[12], frame[13]))

        // 6. Борт (3 байта BCD)
        val tailNumber = bcdToString(byteArrayOf(frame[14], frame[15]))

        // Если рейс или борт не распарсились из-за сбоя BCD
        if (flightName == null || tailNumber == null) return null

        return FlightRecord(
            number = flightNum,
            size = size,
            date = dateStr,
            time = timeStr,
            flight = flightName,
            tail = tailNumber
        )
    }

    // Декодирование одного BCD-байта в целое число (возвращает -1 при ошибке BCD)
    fun bcdToInt(b: Byte): Int {
        val v = b.toInt() and 0xFF
        val high = (v ushr 4) and 0x0F
        val low = v and 0x0F
        
        // Проверка: в BCD полубайты не могут быть больше 9 (0x0A..0x0F)
        if (high > 9 || low > 9) return -1
        
        return high * 10 + low
    }

    // Декодирование BCD-байтов в строку с удалением ведущих нулей (возвращает null при ошибке)
    fun bcdToString(bytes: ByteArray): String? {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val high = (v ushr 4) and 0x0F
            val low = v and 0x0F
            
            if (high > 9 || low > 9) return null
            
            sb.append(high).append(low)
        }
        val result = sb.toString().trimStart('0')
        return if (result.isEmpty()) "0" else result
    }

    private fun logDebug(msg: String) {
        Log.d("ZBN_TOC_PARSER", msg)
        logger?.invoke(msg)
    }
}
