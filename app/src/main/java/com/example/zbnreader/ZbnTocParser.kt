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

        var i = 0
        val recordSize = 32

        while (i <= tocBytes.size - recordSize) {
            val b0 = tocBytes[i].toInt() and 0xFF
            val b1 = tocBytes[i + 1].toInt() and 0xFF

            // Пропуск незаполненных секторов (0xFF 0xFF)
            if (b0 == 0xFF && b1 == 0xFF) {
                i += recordSize
                continue
            }

            // Поиск маркера синхронизации кадра (0x55 0xAA)
            if (b0 == 0x55 && b1 == 0xAA) {
                try {
                    val frameIndex = tocBytes[i + 2].toInt() and 0xFF
                    val recNum = tocBytes[i + 3].toInt() and 0xFF

                    val offset = (tocBytes[i + 4].toLong() and 0xFF) or
                            ((tocBytes[i + 5].toLong() and 0xFF) shl 8) or
                            ((tocBytes[i + 6].toLong() and 0xFF) shl 16) or
                            ((tocBytes[i + 7].toLong() and 0xFF) shl 24)

                    val day = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 8]))
                    val month = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 9]))
                    val year = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 10]))
                    val dateStr = "$day.$month.$year"

                    val startH = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 11]))
                    val startM = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 12]))
                    val startS = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 13]))
                    val startTimeStr = "$startH:$startM:$startS"

                    val endH = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 14]))
                    val endM = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 15]))
                    val endS = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 16]))
                    val endTimeStr = "$endH:$endM:$endS"

                    val durH = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 17]))
                    val durM = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 18]))
                    val durS = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 19]))
                    val durationStr = "$durH:$durM:$durS"

                    val flightNumStr = bcdToString(tocBytes.copyOfRange(i + 20, i + 23)).trimStart('0').ifEmpty { "0" }
                    val tailNumStr = bcdToString(tocBytes.copyOfRange(i + 23, i + 27)).trimStart('0').ifEmpty { "0" }

                    records.add(
                        FlightRecord(
                            number = if (recNum > 0) recNum else frameIndex + 1,
                            sizeBytes = offset,
                            date = dateStr,
                            duration = durationStr,
                            startTime = startTimeStr,
                            endTime = endTimeStr,
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
