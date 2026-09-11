package com.example.zbnreader

import java.util.Locale

class ZbnTocParser {

    /**
     * Декодирует BCD байт. Если байт пустой (0xFF), возвращает "1515" 
     * для точного совпадения с багом отображения штатной программы ПК.
     */
    private fun parseBcdOr15(b: Byte): String {
        val v = b.toInt() and 0xFF
        if (v == 0xFF) return "1515"
        
        val high = (v ushr 4) and 0x0F
        val low = v and 0x0F
        
        val hStr = if (high > 9) "0" else high.toString()
        val lStr = if (low > 9) "0" else low.toString()
        return "$hStr$lStr"
    }

    private fun bcdToStringRaw(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0xFF) {
                sb.append("1515")
            } else {
                val high = (v ushr 4) and 0x0F
                val low = v and 0x0F
                sb.append(if (high > 9) "0" else high.toString())
                sb.append(if (low > 9) "0" else low.toString())
            }
        }
        return sb.toString()
    }

    fun parse(tocBytes: ByteArray): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        if (tocBytes.isEmpty()) return records

        // Истинный размер структуры записи оглавления ЗБН — 32 байта!
        val recordSize = 32
        var i = 0

        while (i <= tocBytes.size - recordSize) {
            val b0 = tocBytes[i].toInt() and 0xFF
            val b1 = tocBytes[i + 1].toInt() and 0xFF

            // Пропуск неразмеченных/пустых блоков памяти (FF FF)
            if (b0 == 0xFF && b1 == 0xFF) {
                i += recordSize
                continue
            }

            try {
                // 1. Номер записи (байты 0, 1 - 16-bit Little Endian)
                val recNum = b0 or (b1 shl 8)

                // 2. Размер файла (байты 2, 3, 4, 5 - 32-bit Integer)
                val size = (tocBytes[i + 2].toLong() and 0xFF) or
                        ((tocBytes[i + 3].toLong() and 0xFF) shl 8) or
                        ((tocBytes[i + 4].toLong() and 0xFF) shl 16) or
                        ((tocBytes[i + 5].toLong() and 0xFF) shl 24)

                // 3. Дата: ДД.ММ.ГГ (байты 6, 7, 8)
                val d = parseBcdOr15(tocBytes[i + 6])
                val m = parseBcdOr15(tocBytes[i + 7])
                val y = parseBcdOr15(tocBytes[i + 8])
                val dateStr = if (d == "1515") "1515.1515.1515" else "$d.$m.20$y"

                // 4. ВремяЗап / Длительность (байты 9, 10, 11)
                val durH = parseBcdOr15(tocBytes[i + 9])
                val durM = parseBcdOr15(tocBytes[i + 10])
                val durS = parseBcdOr15(tocBytes[i + 11])
                val durationStr = if (durH == "1515") "00:00:00" else "$durH:$durM:$durS"

                // 5. Начало (байты 12, 13, 14)
                val startH = parseBcdOr15(tocBytes[i + 12])
                val startM = parseBcdOr15(tocBytes[i + 13])
                val startS = parseBcdOr15(tocBytes[i + 14])
                val startTimeStr = if (startH == "1515") "00:00:00" else "$startH:$startM:$startS"

                // 6. Конец (байты 15, 16, 17)
                val endH = parseBcdOr15(tocBytes[i + 15])
                val endM = parseBcdOr15(tocBytes[i + 16])
                val endS = parseBcdOr15(tocBytes[i + 17])
                val endTimeStr = if (endH == "1515") "" else "$endH:$endM:$endS"

                // 7. Рейс (байты 18, 19)
                val flightNumStr = bcdToStringRaw(tocBytes.copyOfRange(i + 18, i + 20))
                    .trimStart('0')
                    .ifEmpty { "0" }

                // 8. Борт (байты 20, 21, 22)
                val tailNumStr = bcdToStringRaw(tocBytes.copyOfRange(i + 20, i + 23))
                    .trimStart('0')
                    .ifEmpty { "0" }

                records.add(
                    FlightRecord(
                        number = recNum,
                        sizeBytes = size,
                        date = dateStr,
                        duration = durationStr,
                        startTime = startTimeStr,
                        endTime = endTimeStr,
                        flightNum = flightNumStr,
                        tailNum = tailNumStr
                    )
                )
            } catch (e: Exception) {
                // Если кадр поврежден, пропускаем ошибку и идем дальше
            }

            // Жесткий шаг к следующему 32-байтовому кадру
            i += recordSize
        }

        return records
    }
}
