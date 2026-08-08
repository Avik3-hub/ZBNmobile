package com.example.zbn

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZbnTocParser {

    companion object {
        private const val FRAME_SIZE = 16
        private const val SYNC_BYTE_1 = 0x55.toByte()
        private const val SYNC_BYTE_2 = 0xAA.toByte()
    }

    fun parseBuffer(buffer: ByteArray, bytesRead: Int): List<ZbnFlightDescriptor> {
        val descriptors = mutableListOf<ZbnFlightDescriptor>()
        var i = 0

        while (i <= bytesRead - FRAME_SIZE) {
            if (buffer[i + 12] == SYNC_BYTE_1 && buffer[i + 13] == SYNC_BYTE_2) {
                val frame = buffer.copyOfRange(i, i + FRAME_SIZE)
                val descriptor = parseFrame(frame)

                if (descriptor != null) {
                    descriptors.add(descriptor)
                    i += FRAME_SIZE
                    continue
                }
            }
            i++
        }

        return descriptors
    }

    private fun parseFrame(frame: ByteArray): ZbnFlightDescriptor? {
        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        val sectorIndex = bb.get(0).toInt() and 0xFF
        val type = bb.get(1).toInt() and 0xFF

        val addrLsb = bb.get(2).toInt() and 0xFF
        val addrMid = bb.get(3).toInt() and 0xFF
        val addrMsb = bb.get(4).toInt() and 0xFF
        val startAddress = (addrMsb shl 16) or (addrMid shl 8) or addrLsb

        val metadata = ByteArray(6)
        bb.position(5)
        bb.get(metadata, 0, 6)

        val subBlockIndex = bb.get(11).toInt() and 0xFF

        val expectedCrc = bb.getShort(14).toInt() and 0xFFFF
        val calculatedCrc = calculateCrc16(frame, 0, 14)
        val isValidCrc = (expectedCrc == calculatedCrc)

        return ZbnFlightDescriptor(
            sectorIndex = sectorIndex,
            type = type,
            startAddress = startAddress,
            metadataRaw = metadata,
            subBlockIndex = subBlockIndex,
            isValidCrc = isValidCrc
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
