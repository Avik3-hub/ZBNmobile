// 1. Безопасное декодирование одного BCD-байта в целое число (0x93 -> 93)
fun bcdToInt(b: Byte): Int {
    val v = b.toInt() and 0xFF
    val high = (v ushr 4) and 0x0F
    val low = v and 0x0F
    
    // Если полубайт выходит за рамки BCD (например, 0xFF заполнитель), мягко заменяем на 0
    val safeHigh = if (high > 9) 0 else high
    val safeLow = if (low > 9) 0 else low
    
    return safeHigh * 10 + safeLow
}

// 2. Безопасное декодирование BCD-байтов в строку (гарантированно возвращает String)
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

// 3. Безопасный разбор кадра оглавления
fun parseFrameToFlightRecord(frame: ByteArray): FlightRecord? {
    if (frame.size < 16) return null

    // 1. Номер включения (2 байта, Big-Endian)
    val flightNum = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)

    // 2. Размер включения (4 байта)
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

    // 5. Рейс (2 байта BCD: байты 12, 13)
    val flightName = bcdToString(byteArrayOf(frame[12], frame[13]))

    // 6. Борт (проверка длины кадра перед чтением frame[16])
    val tailBytes = if (frame.size >= 17) {
        byteArrayOf(frame[14], frame[15], frame[16])
    } else {
        byteArrayOf(frame[14], frame[15])
    }
    val tailNumber = bcdToString(tailBytes)

    return FlightRecord(
        number = flightNum,
        size = size,
        date = dateStr,
        time = timeStr,
        flight = flightName,
        tail = tailNumber
    )
}
