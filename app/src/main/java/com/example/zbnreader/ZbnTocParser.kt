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

    fun parseFrameToFlightRecord(frame: ByteArray): FlightRecord? {
    if (frame.size < 16) return null

    // 1. Номер включения (2 байта, Big-Endian)
    val flightNum = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)

    // 2. Размер записи (4 байта)
    val size = ((frame[2].toLong() and 0xFF) shl 24) or
               ((frame[3].toLong() and 0xFF) shl 16) or
               ((frame[4].toLong() and 0xFF) shl 8) or
               (frame[5].toLong() and 0xFF)

    // 3. Дата (3 байта BCD: [Год, Месяц, День])
    val year = bcdToInt(frame[6])
    val month = bcdToInt(frame[7])
    val day = bcdToInt(frame[8])
    val dateStr = String.format("%02d.%02d.20%02d", day, month, year)

    // 4. Время (3 байта BCD: [Часы, Минуты, Секунды])
    val hour = bcdToInt(frame[9])
    val min = bcdToInt(frame[10])
    val sec = bcdToInt(frame[11])
    val timeStr = String.format("%02d:%02d:%02d", hour, min, sec)

    // 5. Рейс (2 байта BCD, например [0x93, 0x35] -> "9335")
    val flightName = bcdToString(byteArrayOf(frame[12], frame[13]))

    // 6. Борт (3 байта BCD, например [0x02, 0x22, 0x71] -> "22271")
    val tailNumber = bcdToString(byteArrayOf(frame[14], frame[15]))

    return FlightRecord(
        number = flightNum,
        size = size,
        date = dateStr,
        time = timeStr,
        flight = flightName,
        tail = tailNumber
    )
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
// Преобразование одного BCD-байта (например, 0x93) в десятичное число (93)
fun bcdToInt(b: Byte): Int {
    val high = (b.toInt() shr 4) and 0x0F
    val low = b.toInt() and 0x0F
    return high * 10 + low
}

// Преобразование BCD-байтов в строку с сохранением ведущих нулей
fun bcdToString(bytes: ByteArray): String {
    val sb = StringBuilder()
    for (b in bytes) {
        val high = (b.toInt() shr 4) and 0x0F
        val low = b.toInt() and 0x0F
        sb.append(high).append(low)
    }
    return sb.toString().trimStart('0')
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
