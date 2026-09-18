package com.example.zbnreader

/**
 * Парсер оглавления ЗБН-1-3.
 *
 * Данные получаются после команды 0x4D и состоят из дескрипторов по 16 байт:
 * - байты 0..1: номер включения, little-endian;
 * - байты 2..4: физический адрес блока, little-endian;
 * - байты 12..13: маркер 55 AA;
 * - один дескриптор соответствует 512 байтам записанной информации.
 */
class ZbnTocParser {

    private data class RecordCounter(
        var blocks: Long = 0
    )

    fun parse(tocBytes: ByteArray): List<FlightRecord> {
        if (tocBytes.size < DESCRIPTOR_SIZE) return emptyList()

        val records = linkedMapOf<Int, RecordCounter>()
        var offset = 0

        while (offset <= tocBytes.size - DESCRIPTOR_SIZE) {
            if (isErasedDescriptor(tocBytes, offset)) {
                offset += DESCRIPTOR_SIZE
                continue
            }

            if (!hasValidMarker(tocBytes, offset)) {
                // Ресинхронизация, если чтение USB началось не с границы записи.
                offset += 1
                continue
            }

            val recordNumber = readUInt16Le(tocBytes, offset)

            if (recordNumber != ERASED_RECORD_NUMBER) {
                val counter = records.getOrPut(recordNumber) { RecordCounter() }
                counter.blocks += 1
            }

            offset += DESCRIPTOR_SIZE
        }

        return records.map { (number, counter) ->
            FlightRecord(
                number = number,
                sizeBytes = counter.blocks * DATA_BYTES_PER_DESCRIPTOR,
                date = UNKNOWN,
                duration = UNKNOWN,
                startTime = UNKNOWN,
                endTime = UNKNOWN,
                flightNum = UNKNOWN,
                tailNum = UNKNOWN
            )
        }
    }

    private fun isErasedDescriptor(bytes: ByteArray, offset: Int): Boolean {
        for (index in offset until offset + DESCRIPTOR_SIZE) {
            if ((bytes[index].toInt() and 0xFF) != 0xFF) {
                return false
            }
        }
        return true
    }

    private fun hasValidMarker(bytes: ByteArray, offset: Int): Boolean {
        return (bytes[offset + MARKER_OFFSET].toInt() and 0xFF) == 0x55 &&
            (bytes[offset + MARKER_OFFSET + 1].toInt() and 0xFF) == 0xAA
    }

    private fun readUInt16Le(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    companion object {
        private const val DESCRIPTOR_SIZE = 16
        private const val MARKER_OFFSET = 12
        private const val ERASED_RECORD_NUMBER = 0xFFFF
        private const val DATA_BYTES_PER_DESCRIPTOR = 512L
        private const val UNKNOWN = "—"
    }
}
