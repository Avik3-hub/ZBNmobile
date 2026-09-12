package com.example.zbnreader

class ZbnTocParser {

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

        val packetSize = 16
        var i = 0

        // Поток байт обрабатывается с поддержкой ресинхронизации
        while (i <= tocBytes.size - packetSize) {
            val b0 = tocBytes[i].toInt() and 0xFF
            val b1 = tocBytes[i + 1].toInt() and 0xFF
            val footer1 = tocBytes[i + 12].toInt() and 0xFF
            val footer2 = tocBytes[i + 13].toInt() and 0xFF

            // Проверка маркера начала (23 A6 или 22 A6) и футера (55 AA)[span_0](start_span)[span_0](end_span)
            if ((b0 == 0x23 || b0 == 0x22) && b1 == 0xA6 && footer1 == 0x55 && footer2 == 0xAA) {
                try {
                    // 1. Индекс / Номер записи
                    val recNum = tocBytes[i + 2].toInt() and 0xFF

                    // 2. Полезная нагрузка (9 байт: с индекса i + 3 по i + 11)
                    val payload = tocBytes.copyOfRange(i + 3, i + 12)

                    // Пример маппинга полей из полезной нагрузки (при необходимости скорректируйте смещения под ваш формат):
                    val d = parseBcdOr15(payload[0])
                    val m = parseBcdOr15(payload[1])
                    val y = parseBcdOr15(payload[2])
                    val dateStr = if (d == "1515") "1515.1515.1515" else "$d.$m.20$y"

                    val durH = parseBcdOr15(payload[3])
                    val durM = parseBcdOr15(payload[4])
                    val durS = parseBcdOr15(payload[5])
                    val durationStr = if (durH == "1515") "00:00:00" else "$durH:$durM:$durS"

                    val flightNumStr = bcdToStringRaw(payload.copyOfRange(6, 8))
                        .trimStart('0')
                        .ifEmpty { "0" }

                    val tailNumStr = parseBcdOr15(payload[8])

                    records.add(
                        FlightRecord(
                            number = recNum,
                            sizeBytes = 0L, // Заполняется при необходимости из байтов CRC/структуры
                            date = dateStr,
                            duration = durationStr,
                            startTime = durationStr,
                            endTime = "",
                            flightNum = flightNumStr,
                            tailNum = tailNumStr
                        )
                    )
                } catch (e: Exception) {
                    // Игнорируем поврежденный кадр
                }
                
                // Успешный шаг на фиксированный размер пакета
                i += packetSize
            } else {
                // Ресинхронизация: сдвиг ровно на 1 байт вперед при несовпадении заголовка/футера
                i += 1
            }
        }

        return records
    }
}
