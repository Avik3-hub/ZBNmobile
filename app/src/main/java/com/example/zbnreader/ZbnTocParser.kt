package com.example.zbnreader

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

        val pageSize = 512
        val recordSize = 32
        
        // Внешний цикл: шагаем строго блоками по 512 байт (страницами)
        var pageOffset = 0
        while (pageOffset + pageSize <= tocBytes.size) {
            
            // Внутренний цикл: читаем записи по 32 байта внутри текущей страницы
            var recordOffset = 0
            while (recordOffset + recordSize <= pageSize) {
                val i = pageOffset + recordOffset

                val b0 = tocBytes[i].toInt() and 0xFF
                val b1 = tocBytes[i + 1].toInt() and 0xFF

                // Если пошел паддинг (0xFF), бросаем текущую страницу и переходим к следующей
                if (b0 == 0xFF) {
                    break
                }

                try {
                    // 1. Номер записи (16-bit Little Endian)
                    val recNum = b0 or (b1 shl 8)

                    // 2. Размер файла (32-bit Integer)
                    val size = (tocBytes[i + 2].toLong() and 0xFF) or
                            ((tocBytes[i + 3].toLong() and 0xFF) shl 8) or
                            ((tocBytes[i + 4].toLong() and 0xFF) shl 16) or
                            ((tocBytes[i + 5].toLong() and 0xFF) shl 24)

                    // 3. Дата: ДД.ММ.ГГ
                    val d = parseBcdOr15(tocBytes[i + 6])
                    val m = parseBcdOr15(tocBytes[i + 7])
                    val y = parseBcdOr15(tocBytes[i + 8])
                    val dateStr = if (d == "1515") "1515.1515.1515" else "$d.$m.20$y"

                    // 4. ВремяЗап / Длительность
                    val durH = parseBcdOr15(tocBytes[i + 9])
                    val durM = parseBcdOr15(tocBytes[i + 10])
                    val durS = parseBcdOr15(tocBytes[i + 11])
                    val durationStr = if (durH == "1515") "00:00:00" else "$durH:$durM:$durS"

                    // 5. Начало
                    val startH = parseBcdOr15(tocBytes[i + 12])
                    val startM = parseBcdOr15(tocBytes[i + 13])
                    val startS = parseBcdOr15(tocBytes[i + 14])
                    val startTimeStr = if (startH == "1515") "00:00:00" else "$startH:$startM:$startS"

                    // 6. Конец
                    val endH = parseBcdOr15(tocBytes[i + 15])
                    val endM = parseBcdOr15(tocBytes[i + 16])
                    val endS = parseBcdOr15(tocBytes[i + 17])
                    val endTimeStr = if (endH == "1515") "" else "$endH:$endM:$endS"

                    // 7. Рейс
                    val flightNumStr = bcdToStringRaw(tocBytes.copyOfRange(i + 18, i + 20))
                        .trimStart('0')
                        .ifEmpty { "0" }

                    // 8. Борт
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
                    // Игнорируем битый кадр и идем дальше
                }

                // Шаг к следующей записи внутри страницы
                recordOffset += recordSize
            }

            // Жесткий шаг к следующему 512-байтному блоку
            pageOffset += pageSize
        }

        return records
    }
}
