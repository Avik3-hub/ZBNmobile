package com.example.zbnreader

/**
 * Parses the allocation table returned by ZBN-1-3 after command 0x4D.
 *
 * The format was recovered from a real Serial Port Monitor capture:
 * - one descriptor is 16 bytes;
 * - bytes 0..1 contain the recording number (little-endian);
 * - bytes 2..4 contain a 24-bit physical address (little-endian);
 * - bytes 12..13 contain the descriptor marker 55 AA;
 * - bytes 14..15 contain a checksum (not decoded yet);
 * - every valid descriptor represents 512 bytes of flight data.
 */
class ZbnTocParser {

    private data class RecordAccumulator(
        var descriptorCount: Long = 0,
        var firstAddress: Int = -1,
        var lastAddress: Int = -1
    )

    fun parse(tocBytes: ByteArray): List<FlightRecord> {
        if (tocBytes.size < DESCRIPTOR_SIZE) return emptyList()

        val records = linkedMapOf<Int, RecordAccumulator>()
        var offset = 0

        while (offset <= tocBytes.size - DESCRIPTOR_SIZE) {
            if (isErasedDescriptor(tocBytes, offset)) {
                offset += DESCRIPTOR_SIZE
                continue
            }

            if (!hasValidMarker(tocBytes, offset)) {
                // A USB read can start at an arbitrary byte. Move one byte until
                // the fixed marker is found, then continue in 16-byte steps.
                offset++
                continue
            }

            val recordNumber = readUInt16Le(tocBytes, offset)
            if (recordNumber != ERASED_RECORD_NUMBER) {
                val physicalAddress = readUInt24Le(tocBytes, offset + 2)
                val accumulator = records.getOrPut(recordNumber) { RecordAccumulator() }
                if (accumulator.firstAddress < 0) accumulator.firstAddress = physicalAddress
                accumulator.lastAddress = physicalAddress
                accumulator.descriptorCount++
            }

            offset += DESCRIPTOR_SIZE
        }

        return records.map { (number, descriptor) ->
            val sizeBytes = descriptor.descriptorCount * DATA_BYTES_PER_DESCRIPTOR
            FlightRecord(
                number = number,
                sizeBytes = sizeBytes,
                date = UNKNOWN_VALUE,
                duration = formatDuration(sizeBytes),
                startTime = UNKNOWN_VALUE,
                endTime = UNKNOWN_VALUE,
                flightNum = UNKNOWN_VALUE,
                tailNum = UNKNOWN_VALUE,
                startAddress = alignToTransferPage(descriptor.lastAddress),
                endAddress = alignToTransferPage(descriptor.firstAddress)
            )
        }
    }

    private fun isErasedDescriptor(bytes: ByteArray, offset: Int): Boolean {
        for (index in offset until offset + DESCRIPTOR_SIZE) {
            if ((bytes[index].toInt() and 0xFF) != 0xFF) return false
        }
        return true
    }

    private fun hasValidMarker(bytes: ByteArray, offset: Int): Boolean =
        (bytes[offset + MARKER_OFFSET].toInt() and 0xFF) == MARKER_FIRST &&
            (bytes[offset + MARKER_OFFSET + 1].toInt() and 0xFF) == MARKER_SECOND

    private fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt24Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)

    private fun alignToTransferPage(address: Int): Int =
        if (address < 0) address else address and TRANSFER_PAGE_MASK

    private fun formatDuration(sizeBytes: Long): String {
        val totalSeconds = sizeBytes / ARINC_573_BYTES_PER_SECOND
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "${hours.toString().padStart(2, '0')}:" +
            "${minutes.toString().padStart(2, '0')}:" +
            seconds.toString().padStart(2, '0')
    }

    companion object {
        const val DESCRIPTOR_SIZE = 16
        const val DATA_BYTES_PER_DESCRIPTOR = 512L
        const val ARINC_573_BYTES_PER_SECOND = 96L

        private const val MARKER_OFFSET = 12
        private const val MARKER_FIRST = 0x55
        private const val MARKER_SECOND = 0xAA
        private const val ERASED_RECORD_NUMBER = 0xFFFF
        private const val TRANSFER_PAGE_MASK = 0xFFFFE0
        private const val UNKNOWN_VALUE = "—"
    }
}
