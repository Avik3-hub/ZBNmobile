package com.example.zbnreader

import android.util.Log

class ZbnTocParser(private val logger: ((String) -> Unit)? = null) {

    companion object {
        private const val FRAME_SIZE = 16
        private const val SYNC_BYTE_1 = 0x55.toByte()
        private const val SYNC_BYTE_2 = 0xAA.toByte()
    }

    fun parseBuffer(buffer: ByteArray, bytesRead: Int): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        var i = 0

        while (i <= bytesRead - FRAME_SIZE) {
            // Проверяем маркер 0x55 0xAA в начале кадра (байты 0 и 1)
            if (buffer[i] == SYNC_BYTE_1 && buffer[i + 1] == SYNC_BYTE_2) {
                val frame = buffer.copyOfRange(i, i + FRAME_SIZE)
                val record = parseFrameToFlightRecord(frame, records.size + 1)

                if (record != null) {
                    records.add(record)
                    i += FRAME_SIZE
                    continue
                }
            }
            i++
        }

        return records
    }

    private fun parseFrameToFlightRecord(frame: ByteArray, sectorIndex: Int): FlightRecord? {
        try {
            val startAddress = parseUInt32LE(frame, 2)

            val year = bcdToDec(frame[6].toInt())
            val month = bcdToDec(frame[7].toInt())
            val day = bcdToDec(frame[8].toInt())

            val hour = bcdToDec(frame[9].toInt())
            val minute = bcdToDec(frame[10].toInt())
            val second = bcdToDec(frame[11].toInt())

            val flightNumVal = (frame[12].toInt() and 0xFF).toString()
            val tailNumVal = (frame[13].toInt() and 0xFF).toString()

            val dateFormatted = String.format("%02d.%02d.20%02d", day, month, year)
            val startTimeFormatted = String.format("%02d:%02d:%02d", hour, minute, second)

            return FlightRecord(
                number = sectorIndex,
                sizeBytes = if (startAddress > 0) startAddress else (sectorIndex * 1024L),
                date = dateFormatted,
                duration = "--:--",
                startTime = startTimeFormatted,
                endTime = "-",
                flightNum = flightNumVal,
                tailNum = tailNumVal
            )
        } catch (e: Exception) {
            logDebug("Ошибка разбора отдельного кадра: ${e.message}")
            return null
        }
    }

    private fun bcdToDec(b: Int): Int {
        val high = (b ushr 4) and 0x0F
        val low = b and 0x0F
        return if (high <= 9 && low <= 9) {
            high * 10 + low
        } else {
            b % 100
        }
    }

    private fun parseUInt32LE(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0L
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
