package com.example.zbnreader

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZbnTocParser {

    companion object {
        private const val FRAME_SIZE = 16
        private const val SYNC_BYTE_1 = 0x55.toByte()
        private const val SYNC_BYTE_2 = 0xAA.toByte()
    }

    fun parseBuffer(buffer: ByteArray, bytesRead: Int): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        var i = 0

        while (i <= bytesRead - FRAME_SIZE) {
            // Проверяем маркер 0x55 0xAA на байтах 12 и 13
            if (buffer[i + 12] == SYNC_BYTE_1 && buffer[i + 13] == SYNC_BYTE_2) {
                val frame = buffer.copyOfRange(i, i + FRAME_SIZE)
                val record = parseFrameToFlightRecord(frame)

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

    private fun parseFrameToFlightRecord(frame: ByteArray): FlightRecord? {
        // Проверка CRC16 (байты 14-15)
        val expectedCrc = ((frame[15].toInt() and 0xFF) shl 8) or (frame[14].toInt() and 0xFF)
        val calculatedCrc = calculateCrc16(frame, 0, 14)

        if (expectedCrc != calculatedCrc) {
            return null
        }

        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        // Байт 0: Индекс сектора / № включения
        val sectorIndex = bb.get(0).toInt() and 0xFF

        // Байты 2..4: 24-битный адрес смещения в памяти
        val addrLsb = bb.get(2).toInt() and 0xFF
        val addrMid = bb.get(3).toInt() and 0xFF
        val addrMsb = bb.get(4).toInt() and 0xFF
        val startAddress = (addrMsb shl 16) or (addrMid shl 8) or addrLsb

        // Байты 5..10: Метаданные (время, дата, борт, рейс)
        val meta5 = frame[5]
        val meta6 = frame[6]
        val meta7 = frame[7]
        val meta8 = frame[8]
        val meta9 = frame[9]
        val meta10 = frame[10]

        // --- РАЗБОР ВРЕМЕНИ И ДАТЫ (BCD / Decimal) ---
        val hour = bcdToInt(meta6)
        val min = bcdToInt(meta7)
        val sec = bcdToInt(meta8)

        val day = bcdToInt(meta5)
        val month = bcdToInt(meta9)
        val year = 26 // По умолчанию текущий год или из байта года

        val startTimeFormatted = String.format("%02d:%02d:%02d", hour.coerceIn(0, 23), min.coerceIn(0, 59), sec.coerceIn(0, 59))
        val dateFormatted = String.format("%02d.%02d.%02d", day.coerceIn(1, 31), month.coerceIn(1, 12), year)

        // Бортовой номер и номер рейса
        val flightNumVal = (meta6.toInt() and 0xFF).toString()
        val tailNumVal = String.format("%02X%02X", meta9, meta10)

        return FlightRecord(
            number = sectorIndex,
            sizeBytes = startAddress.toLong(),
            date = dateFormatted,
            duration = "--:--",
            startTime = startTimeFormatted,
            endTime = "-",
            flightNum = flightNumVal,
            tailNum = tailNumVal
        )
    }

    /**
     * Конвертация BCD (Binary-Coded Decimal) в обычный Int.
     * Если байт не является валидным BCD, возвращается его прямое значение.
     */
    private fun bcdToInt(b: Byte): Int {
        val high = (b.toInt() ushr 4) and 0x0F
        val low = b.toInt() and 0x0F
        return if (high <= 9 && low <= 9) {
            high * 10 + low
        } else {
            b.toInt() and 0xFF
        }
    }

    private fun calculateCrc16(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (j in offset until (offset + length)) {
            crc = crc xor (bytes[j].toInt() and 0xFF)
            for (k in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}
