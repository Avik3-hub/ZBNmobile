package com.example.zbnreader

import java.util.Locale

/**
 * Модель записи оглавления ЗБН (16 байт на кадр)
 */
data class FlightRecord(
    val number: Int,
    val startOffset: Long,
    val endOffset: Long,
    val sizeBytes: Long,
    val date: String = "",
    val duration: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val flightNum: String = "",
    val tailNum: String = ""
)

class ZbnTocParser {

    /**
     * Разбор 16-байтовых кадров оглавления (TOC)
     */
    fun parse(tocBytes: ByteArray): List<FlightRecord> {
        val records = mutableListOf<FlightRecord>()
        if (tocBytes.isEmpty()) return records

        val frameSize = 16

        var i = 0
        while (i <= tocBytes.size - frameSize) {
            // Пропуск незаполненных/стёртых секторов Flash (все байты FF)
            var isEraseBlock = true
            for (j in 0 until frameSize) {
                if ((tocBytes[i + j].toInt() and 0xFF) != 0xFF) {
                    isEraseBlock = false
                    break
                }
            }

            if (isEraseBlock) {
                i += frameSize
                continue
            }

            // Проверка сигнатуры кадра 0x55 0xAA на байтах 12 и 13
            val marker1 = tocBytes[i + 12].toInt() and 0xFF
            val marker2 = tocBytes[i + 13].toInt() and 0xFF

            if (marker1 == 0x55 && marker2 == 0xAA) {
                try {
                    // Начальное смещение (байты 2..4, Little-Endian)
                    val startAddr = (tocBytes[i + 2].toLong() and 0xFF) or
                            ((tocBytes[i + 3].toLong() and 0xFF) shl 8) or
                            ((tocBytes[i + 4].toLong() and 0xFF) shl 16)

                    // Конечное смещение (байты 8..10, Little-Endian)
                    val endAddr = (tocBytes[i + 8].toLong() and 0xFF) or
                            ((tocBytes[i + 9].toLong() and 0xFF) shl 8) or
                            ((tocBytes[i + 10].toLong() and 0xFF) shl 16)

                    // Номер записи / индекс полета (байт 11)
                    val recNum = tocBytes[i + 11].toInt() and 0xFF

                    // Расчет размера записи
                    val calculatedSize = if (endAddr >= startAddr) {
                        endAddr - startAddr
                    } else {
                        0L
                    }

                    records.add(
                        FlightRecord(
                            number = recNum,
                            startOffset = startAddr,
                            endOffset = endAddr,
                            sizeBytes = calculatedSize
                        )
                    )
                    i += frameSize
                } catch (_: Exception) {
                    i++
                }
            } else {
                i++
            }
        }

        // Сортировка записей ring-буфера по их порядковому номеру
        return records.sortedBy { it.number }
    }
}
