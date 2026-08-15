package com.example.zbnreader

import java.util.Locale

object ZbnTocParser {

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
     * 2. Безопасное декодирование BCD-байтов в строку
     */
    fun bcdToString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val high = (v ushr 4) and 0x0F
            val low = v and 0x0F

            val safeHigh = if (high > 9) '0' else ('0' + high)
            val safeLow = if (low > 9) '0' else ('0' + low)

            sb.append(safeHigh).append(safeLow)
        }
        val result = sb.toString().trimStart('0')
        return if (result.isEmpty()) "0" else result
    }

    /**
     * 3. Разбор одного кадра оглавления (16-17 байт)
     */
    fun parseFrameToFlightRecord(frame: ByteArray): FlightRecord? {
        if (frame.size < 16) return null

        val parsedIncNumber = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)

        val parsedSizeBytes = ((frame[2].toLong() and 0xFF) shl 24) or
                ((frame[3].toLong() and 0xFF) shl 16) or
                ((frame[4].toLong() and 0xFF) shl 8) or
                (frame[5].toLong() and 0xFF)

        val year = bcdToInt(frame[6])
        val month = bcdToInt(frame[7])
        val day = bcdToInt(frame[8])
        val dateStr = String.format(Locale.getDefault(), "%02d.%02d.20%02d", day, month, year)

        val hour = bcdToInt(frame[9])
        val min = bcdToInt(frame[10])
        val sec = bcdToInt(frame[11])
        val timeStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", hour, min, sec)

        val parsedFlightNum = bcdToString(byteArrayOf(frame[12], frame[13]))

        val tailBytes = if (frame.size >= 17) {
            byteArrayOf(frame[14], frame[15], frame[16])
        } else {
            byteArrayOf(frame[14], frame[15])
        }
        val parsedTailNum = bcdToString(tailBytes)

        return FlightRecord(
            number = parsedIncNumber,
            sizeBytes = parsedSizeBytes,
            date = dateStr,
            duration = "",
            startTime = timeStr,
            endTime = "",
            flightNum = parsedFlightNum,
            tailNum = parsedTailNum
        )
    }

    /**
     * 4. Метод полного разбора буфера оглавления на список записей FlightRecord
     */
    fun parse(data: ByteArray, frameSize: Int = 16): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        var offset = 0
        while (offset + frameSize <= data.size) {
            val frame = data.copyOfRange(offset, offset + frameSize)
            val record = parseFrameToFlightRecord(frame)
            if (record != null && record.number != 0 && record.number != 0xFFFF) {
                records.add(record)
            }
            offset += frameSize
        }
        return records
    }
}
