package com.example.zbnreader

import java.util.Locale

class ZbnTocParser {

    private fun bcdToInt(b: Byte): Int {
        val v = b.toInt() and 0xFF
        val high = (v ushr 4) and 0x0F
        val low = v and 0x0F

        val safeHigh = if (high > 9) 0 else high
        val safeLow = if (low > 9) 0 else low

        return safeHigh * 10 + safeLow
    }

    private fun bcdToString(bytes: ByteArray): String {
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
     * Расчет контрольной суммы CRC16 (полином A001) для диапазона байтов
     */
    fun calculateCrc16(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until (offset + length)) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Метод для разбора оглавления (TOC) накопителя ЗБН
     */
    fun parse(tocBytes: ByteArray): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        if (tocBytes.isEmpty()) return records

        val recordSize = 16
        var i = 0

        while (i <= tocBytes.size - recordSize) {
            val b0 = tocBytes[i].toInt() and 0xFF
            val b1 = tocBytes[i + 1].toInt() and 0xFF

            // 1. Фильтрация: пропуск блоков 0xFF (неразмеченные/стертые сектора)
            if (b0 == 0xFF && b1 == 0xFF) {
                i += recordSize
                continue
            }

            // 2. Валидация байтов синхронизации (0x55 0xAA)
            if (b0 == 0x55 && b1 == 0xAA) {
                try {
                    // 3. Расчет и проверка CRC16 для первых 14 байт кадра
                    val calculatedCrc = calculateCrc16(tocBytes, i, 14)
                    val receivedCrc = (tocBytes[i + 14].toInt() and 0xFF) or
                            ((tocBytes[i + 15].toInt() and 0xFF) shl 8)

                    // Если контрольная сумма не совпадает, кадр поврежден — ищем следующий
                    if (calculatedCrc != receivedCrc) {
                        i++
                        continue
                    }

                    val recNum = tocBytes[i + 2].toInt() and 0xFF

                    // Размер / Смещение (байты 3, 4, 5)
                    val size = (tocBytes[i + 3].toLong() and 0xFF) or
                            ((tocBytes[i + 4].toLong() and 0xFF) shl 8) or
                            ((tocBytes[i + 5].toLong() and 0xFF) shl 16)

                    // Дата: ДД.ММ.20ГГ (байты 6, 7, 8)
                    val day = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 6]))
                    val month = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 7]))
                    val yearRaw = bcdToInt(tocBytes[i + 8])
                    val year = String.format(Locale.US, "20%02d", yearRaw)
                    val dateStr = "$day.$month.$year"

                    // Время: ЧЧ:ММ:СС (байты 9, 10, 11)
                    val h = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 9]))
                    val m = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 10]))
                    val s = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 11]))
                    val timeStr = "$h:$m:$s"

                    // Номер рейса (байты 12, 13)
                    val flightNumStr = bcdToString(tocBytes.copyOfRange(i + 12, i + 14))
                        .trimStart('0')
                        .ifEmpty { "0" }

                    records.add(
                        FlightRecord(
                            number = recNum,
                            sizeBytes = size,
                            date = dateStr,
                            duration = timeStr,
                            startTime = timeStr,
                            endTime = timeStr,
                            flightNum = flightNumStr,
                            tailNum = "0"
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
