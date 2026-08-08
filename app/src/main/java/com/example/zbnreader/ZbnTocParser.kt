package com.example.zbnreader

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZbnTocParser {

    companion object {
        private const val FRAME_SIZE = 16
        private const val SYNC_BYTE_1 = 0x55.toByte()
        private const val SYNC_BYTE_2 = 0xAA.toByte()
    }

    /**
     * Разбор входящего буфера байт сразу в список ваших объектов FlightRecord
     */
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
        // Проверка контрольной суммы (байты 14-15)
        val expectedCrc = ((frame[15].toInt() and 0xFF) shl 8) or (frame[14].toInt() and 0xFF)
        val calculatedCrc = calculateCrc16(frame, 0, 14)
        
        if (expectedCrc != calculatedCrc) {
            return null // Игнорируем поврежденный кадр
        }

        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        // Порядковый номер/индекс кадра (байт 0)
        val sectorIndex = bb.get(0).toInt() and 0xFF

        // 24-битный адрес/смещение в памяти ЗБН (байты 2..4)
        val addrLsb = bb.get(2).toInt() and 0xFF
        val addrMid = bb.get(3).toInt() and 0xFF
        val addrMsb = bb.get(4).toInt() and 0xFF
        val startAddress = (addrMsb shl 16) or (addrMid shl 8) or addrLsb

        // Читаем байты метаданных (№ рейса и борта)
        val flightByte = bb.get(6).toInt() and 0xFF
        val tailMsb = bb.get(9).toInt() and 0xFF
        val tailLsb = bb.get(10).toInt() and 0xFF

        // Заполняем вашу имеющуюся модель FlightRecord
        return FlightRecord(
            number = sectorIndex,
            sizeBytes = startAddress.toLong(),
            date = "--.--.----",       // Заполнится при детальном прочтении заголовка полета
            duration = "--:--",
            startTime = "--:--",
            endTime = "--:--",
            flightNum = flightByte.toString(),
            tailNum = String.format("%02X%02X", tailMsb, tailLsb)
        )
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
