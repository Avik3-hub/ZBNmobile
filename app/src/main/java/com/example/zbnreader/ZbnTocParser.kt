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
            // Игнорируем заполнители 0x0F / 15
            if (high < 10) sb.append(high)
            if (low < 10) sb.append(low)
        }
        return sb.toString()
    }

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

    fun parse(tocBytes: ByteArray): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        if (tocBytes.isEmpty()) return records

        val recordSize = 16
        var i = 0

        while (i <= tocBytes.size - recordSize) {
            val b0 = tocBytes[i].toInt() and 0xFF
            val b1 = tocBytes[i + 1].toInt() and 0xFF

            // Пропуск стертых секторов флеш-памяти (0xFF 0xFF)
            if (b0 == 0xFF && b1 == 0xFF) {
                i += recordSize
                continue
            }

            // Поиск маркер-синхронизации кадра (0x55 0xAA)
            if (b0 == 0x55 && b1 == 0xAA) {
                try {
                    // Номер записи (байты 2, 3 - 16-bit Short)
                    val recNum = (tocBytes[i + 2].toInt() and 0xFF) or
                            ((tocBytes[i + 3].toInt() and 0xFF) shl 8)

                    // Размер записи в байтах (байты 4, 5, 6 - 24-bit Integer)
                    val size = (tocBytes[i + 4].toLong() and 0xFF) or
                            ((tocBytes[i + 5].toLong() and 0xFF) shl 8) or
                            ((tocBytes[i + 6].toLong() and 0xFF) shl 16)

                    // Дата: ДД.ММ.20ГГ (байты 7, 8, 9)
                    val day = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 7]))
                    val month = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 8]))
                    val yearRaw = bcdToInt(tocBytes[i + 9])
                    val dateStr = "$day.$month.20${String.format(Locale.US, "%02d", yearRaw)}"

                    // Время начала записи: ЧЧ:ММ:СС (байты 10, 11)
                    val startH = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 10]))
                    val startM = String.format(Locale.US, "%02d", bcdToInt(tocBytes[i + 11]))
                    val startTimeStr = "$startH:$startM:00"

                    // Рейс (байт 12)
                    val flightNumStr = bcdToString(byteArrayOf(tocBytes[i + 12]))
                        .trimStart('0')
                        .ifEmpty { "0" }

                    // Бортовой номер (байты 13, 14, 15)
                    val tailNumStr = bcdToString(tocBytes.copyOfRange(i + 13, i + 16))
                        .trimStart('0')
                        .ifEmpty { "22963" }

                    records.add(
                        FlightRecord(
                            number = recNum,
                            sizeBytes = size,
                            date = dateStr,
                            duration = "00:00:00",
                            startTime = startTimeStr,
                            endTime = "",
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
