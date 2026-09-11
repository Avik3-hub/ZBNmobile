package com.example.zbnreader

import java.util.Locale

/**
 * Модель данных записи включения ЗБН
 */
data class FlightRecord(
    val number: Int,
    val sizeBytes: Long,
    val date: String,
    val duration: String,
    val startTime: String,
    val endTime: String,
    val flightNum: String,
    val tailNum: String
)

class ZbnTocParser {

    /**
     * 1. Безопасное декодирование одного BCD-байта в целое число (0x93 -> 93)
     */
    fun bcdToInt(b: Byte): Int {
        val v = b.toInt() and 0xFF
        val high = (v ushr 4) and 0x0F
        val low = v and 0x0F

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
     * 3. Метод для разбора оглавления (TOC) накопителя ЗБН
     */
    fun parse(tocBytes: ByteArray): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        if (tocBytes.isEmpty()) return records

        val recordSize = 16 // 16-байтовая кадровая структура оглавления ЗБН
        var i = 0

        while (i <= tocBytes.size - recordSize) {
            val b0 = tocBytes[i].toInt() and 0xFF
            val b1 = tocBytes[i + 1].toInt() and 0xFF

            // Пропуск незаполненных/стертых секторов флеш-памяти (0xFF 0xFF)
            if (b0 == 0xFF && b1 == 0xFF) {
                i += recordSize
                continue
            }

            // Поиск маркера синхронизации кадра (0x55 0xAA)
            if (b0 == 0x55 && b1 == 0xAA) {
                try {
                    val recNum = tocBytes[i + 2].toInt() and 0xFF

                    // Размер / Смещение (байты 3, 4, 5)
                    val size = (tocBytes[i + 3].toLong() and 0xFF) or
                            ((tocBytes[i + 4].toLong() and 0xFF) shl 8) or
                            ((tocBytes[i + 5].toLong() and 0xFF) shl 16)

                    // Дата: ДД.ММ.ГГ (байты 6, 7, 8)
                    val day = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 6]))
                    val month = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 7]))
                    val year = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 8]))
                    val dateStr = "$day.$month.$year"

                    // Время: ЧЧ:ММ:СС (байты 9, 10, 11)
                    val h = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 9]))
                    val m = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 10]))
                    val s = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 11]))
                    val timeStr = "$h:$m:$s"

                    // Номер рейса (байты 12, 13)
                    val flightNumStr = bcdToString(tocBytes.copyOfRange(i + 12, i + 14)).trimStart('0').ifEmpty { "0" }

                    // Бортовой номер (байты 14, 15)
                    val tailNumStr = bcdToString(tocBytes.copyOfRange(i + 14, i + 16)).trimStart('0').ifEmpty { "0" }

                    records.add(
                        FlightRecord(
                            number = recNum,
                            sizeBytes = size,
                            date = dateStr,
                            duration = timeStr,
                            startTime = timeStr,
                            endTime = timeStr,
                            flightNum = flightNumStr,
                            tailNum = tailNumStr
                        )
                    )
                    i += recordSize
                } catch (_: Exception) {
                    i++
                }
            } else {
                i++
            }
        }

        return records
    }
}
