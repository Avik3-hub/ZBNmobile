package com.example.zbnreader

import java.util.Locale

class ZbnTocParser {

    /**
     * 1. Безопасное декодирование одного BCD-байта в целое число (0x93 -> 93)
     */
    fun bcdToInt(b: Byte): Int {
        val v = b.toInt() and 0xFF
        val high = (v ushr 4) and 0x0F
        val low = v and 0x0F

        // Если полубайт выходит за рамки BCD, заменяем на 0 для предотвращения крашей
        val safeHigh = if (high > 9) 0 else high
        val safeLow = if (low > 9) 0 else low

        return safeHigh * 10 + safeLow
    }

    /**
     * 2. Безопасное декодирование массива BCD-байтов в строку
     */
    fun bcdToString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val high = (v ushr 4) and 0x0F
            val low = v and 0x0F
            sb.append(if (high > 9) 0 else high)
            sb.append(if (low > 9) 0 else low)
        }
        return sb.toString()
    }

    /**
     * 3. Метод для разбора оглавления (TOC)
     */
    fun parse(tocBytes: ByteArray): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        // Логика разбора байтов оглавления под структуру вашего накопителя ЗБН
        return records
    }
}
