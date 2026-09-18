package com.example.zbnreader

/**
 * One ARINC-573 subframe (one second at 64 words/second).
 */
data class Subframe(
    val subframeIndex: Int,
    val words: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Subframe
        return subframeIndex == other.subframeIndex && words.contentEquals(other.words)
    }

    override fun hashCode(): Int {
        var result = subframeIndex
        result = 31 * result + words.contentHashCode()
        return result
    }
}

/**
 * Decoder for the ARINC-573 stream stored by ZBN-1-3.
 *
 * The storage format was recovered from a real BUR-1/ZBN-1-3 capture:
 * - 64 words are recorded per second;
 * - every word is 12 bits, therefore one second occupies exactly 96 bytes;
 * - bits inside every byte are stored least-significant bit first;
 * - a transfer block contains 512 data bytes followed by a 16-byte descriptor;
 * - a 512-byte physical ZBN sector can start at any bit phase;
 * - valid subframes are confirmed by the next sync word exactly 64 words later.
 *
 * The 16-byte transfer descriptors must be removed before words are decoded.
 * After that the payload sectors form one continuous packed bit stream.
 */
class Arinc717Parser(
    private val wordsPerSubframe: Int = WORDS_PER_SUBFRAME,
    private val sectorSizeBytes: Int = ZBN_SECTOR_SIZE_BYTES
) {

    init {
        require(wordsPerSubframe > 0) { "wordsPerSubframe must be positive" }
        require(sectorSizeBytes > 0) { "sectorSizeBytes must be positive" }
    }

    fun parseRawBytes(rawData: ByteArray): List<Subframe> {
        if (rawData.isEmpty()) return emptyList()
        return parsePayload(stripTransferDescriptors(rawData))
    }

    fun parsePackedPayload(payload: ByteArray): List<Subframe> = parsePayload(payload)

    /** Removes the 16-byte trailer added by ZBN to each 512-byte data block. */
    fun stripTransferDescriptors(rawData: ByteArray): ByteArray {
        val transferBlockSize = sectorSizeBytes + TRANSFER_DESCRIPTOR_SIZE_BYTES
        if (rawData.size < transferBlockSize || rawData.size % transferBlockSize != 0) {
            return rawData
        }

        val blockCount = rawData.size / transferBlockSize
        for (blockIndex in 0 until blockCount) {
            val descriptorOffset = blockIndex * transferBlockSize + sectorSizeBytes
            val markerOffset = descriptorOffset + TRANSFER_MARKER_OFFSET
            val hasMarker =
                (rawData[markerOffset].toInt() and 0xFF) == TRANSFER_MARKER_FIRST &&
                    (rawData[markerOffset + 1].toInt() and 0xFF) == TRANSFER_MARKER_SECOND
            val isErased = (descriptorOffset until descriptorOffset + TRANSFER_DESCRIPTOR_SIZE_BYTES)
                .all { (rawData[it].toInt() and 0xFF) == 0xFF }
            if (!hasMarker && !isErased) {
                return rawData
            }
        }

        val payload = ByteArray(blockCount * sectorSizeBytes)
        for (blockIndex in 0 until blockCount) {
            rawData.copyInto(
                destination = payload,
                destinationOffset = blockIndex * sectorSizeBytes,
                startIndex = blockIndex * transferBlockSize,
                endIndex = blockIndex * transferBlockSize + sectorSizeBytes
            )
        }
        return payload
    }

    private fun parsePayload(data: ByteArray): List<Subframe> {
        val output = mutableListOf<Subframe>()
        val bitCount = data.size * BITS_PER_BYTE
        val subframeBitCount = wordsPerSubframe * BITS_PER_WORD
        var searchBit = 0

        while (searchBit + BITS_PER_WORD <= bitCount) {
            val sync = readWord(data, bitCount, searchBit) ?: break
            val subframeNumber = getSubframeNumber(sync)

            if (subframeNumber == null || !hasExpectedNextSync(
                    data,
                    bitCount,
                    searchBit,
                    subframeNumber,
                    subframeBitCount
                )
            ) {
                searchBit++
                continue
            }

            // A pair of correctly spaced sync words establishes a real chain.
            // Decode all complete subframes in that chain without relying on a
            // fixed byte or nibble alignment.
            var currentBit = searchBit
            var expectedSubframe = subframeNumber

            while (currentBit + subframeBitCount <= bitCount) {
                val currentSync = readWord(data, bitCount, currentBit) ?: break
                if (getSubframeNumber(currentSync) != expectedSubframe) break

                val words = IntArray(wordsPerSubframe)
                var complete = true
                for (wordIndex in 0 until wordsPerSubframe) {
                    val word = readWord(
                        data,
                        bitCount,
                        currentBit + wordIndex * BITS_PER_WORD
                    )
                    if (word == null) {
                        complete = false
                        break
                    }
                    words[wordIndex] = word
                }

                if (!complete) break
                output.add(Subframe(expectedSubframe, words))

                currentBit += subframeBitCount
                expectedSubframe = nextSubframeNumber(expectedSubframe)
            }

            searchBit = maxOf(searchBit + 1, currentBit)
        }
        return output
    }

    private fun hasExpectedNextSync(
        data: ByteArray,
        bitCount: Int,
        currentBit: Int,
        currentSubframe: Int,
        subframeBitCount: Int
    ): Boolean {
        val nextBit = currentBit + subframeBitCount
        val nextSync = readWord(data, bitCount, nextBit) ?: return false
        return getSubframeNumber(nextSync) == nextSubframeNumber(currentSubframe)
    }

    /**
     * Reads one 12-bit word from the ZBN bit stream.
     *
     * Bytes are traversed in normal address order, but bits within a byte are
     * consumed from bit 0 to bit 7. The first consumed bit is the most
     * significant bit of the resulting ARINC word.
     */
    private fun readWord(
        data: ByteArray,
        bitCount: Int,
        bitOffset: Int
    ): Int? {
        if (bitOffset < 0 || bitOffset + BITS_PER_WORD > bitCount) return null

        var value = 0
        for (bitIndex in 0 until BITS_PER_WORD) {
            val streamBit = bitOffset + bitIndex
            val sourceByte = data[streamBit / BITS_PER_BYTE].toInt() and 0xFF
            val sourceBit = (sourceByte ushr (streamBit % BITS_PER_BYTE)) and 1
            value = (value shl 1) or sourceBit
        }
        return value
    }

    private fun nextSubframeNumber(current: Int): Int = if (current == 4) 1 else current + 1

    private fun getSubframeNumber(word: Int): Int? = when (word) {
        SYNC_SF1 -> 1
        SYNC_SF2 -> 2
        SYNC_SF3 -> 3
        SYNC_SF4 -> 4
        else -> null
    }

    companion object {
        const val SYNC_SF1 = 0x247
        const val SYNC_SF2 = 0x5B8
        const val SYNC_SF3 = 0xA47
        const val SYNC_SF4 = 0xDB8

        const val WORDS_PER_SUBFRAME = 64
        const val BITS_PER_WORD = 12
        const val ZBN_SECTOR_SIZE_BYTES = 512
        const val TRANSFER_DESCRIPTOR_SIZE_BYTES = 16
        const val BYTES_PER_SECOND = 96L

        private const val BITS_PER_BYTE = 8
        private const val TRANSFER_MARKER_OFFSET = 12
        private const val TRANSFER_MARKER_FIRST = 0x55
        private const val TRANSFER_MARKER_SECOND = 0xAA

        fun durationSeconds(sizeBytes: Long): Long =
            if (sizeBytes <= 0) 0 else sizeBytes / BYTES_PER_SECOND
    }
}
