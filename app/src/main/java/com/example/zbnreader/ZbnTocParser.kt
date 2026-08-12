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

        // Ищем маркер 0x55 0xAA как НАЧАЛО кадра (байты 0 и 1)
        while (i <= bytesRead - FRAME_SIZE) {
            if (buffer[i] == SYNC_BYTE_1 && buffer[i + 1] == SYNC_BYTE_2) {
                val frame = buffer.copyOfRange(i, i + FRAME_SIZE)
                val record = parseFrameToFlightRecord(frame)

                if (record != null) {
                    records.add(record)
                    i += FRAME_SIZE // Кадр разобран — переходим к следующему
                    continue
                }
            }
            i++ // Сдвиг скользящего окна при сбое синхронизации
        }

        return records
    }

    private fun parseFrameToFlightRecord(frame: ByteArray): FlightRecord? {
        try {
            // 1. Номер включения (байты 6..7, Little-Endian)
            val sessionNum = ((frame[7].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)

            // 2. Бортовой номер и номер рейса (байты 4..5)
            val tailNumVal = String.format("%02X%02X", frame[4], frame[5])
            val flightNumVal = sessionNum.toString()

            // 3. Размер / Адрес (байты 8..11, UInt32 Little-Endian)
            val sizeBytes = parseUInt32LE(frame, 8)

            // 4. Время начала (байты 12..13, BCD ЧЧ:ММ)
            val hour = bcdToDec(frame[12].toInt() and 0xFF)
            val minute = bcdToDec(frame[13].toInt() and 0xFF)
            val startTimeFormatted = String.format("%02d:%02d", hour, minute)

            // 5. Дата (байты 14..15, BCD ДД.ММ)
            val day = bcdToDec(frame[14].toInt() and 0xFF)
            val month = bcdToDec(frame[15].toInt() and 0xFF)
            val dateFormatted = String.format("%02d.%02d.2026", day, month)

            return FlightRecord(
                number = sessionNum,
                sizeBytes = if (sizeBytes > 0) sizeBytes else 65536L,
                date = dateFormatted,
                duration = "--:--",
                startTime = startTimeFormatted,
                endTime = "-",
                flightNum = flightNumVal,
                tailNum = tailNumVal
            )
        } catch (e: Exception) {
            logDebug("Ошибка разбора кадра: ${e.message}")
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
