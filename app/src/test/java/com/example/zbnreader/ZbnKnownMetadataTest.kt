package com.example.zbnreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generated ARINC-573 words reproduce published reference values, not real flight samples. */
class ZbnKnownMetadataTest {
    @Test fun decodesKnownFlightAndBoardAcrossSeveralDates() {
        val knownStarts = listOf(
            "24.09.26" to "07:34:10", // ZBN 95
            "24.09.26" to "06:26:34", // ZBN 94
            "24.09.26" to "05:52:46", // ZBN 93
            "22.09.26" to "07:04:39", // ZBN 92
            "22.09.26" to "06:43:19", // ZBN 91
            "22.09.26" to "05:56:15"  // ZBN 90
        )
        for ((date, time) in knownStarts) {
            val frames = frames(date, time)
            assertEquals(ZbnDecodedMetadata(time, date, "9339", "22466"),
                ZbnMetadataDecoder().decode(frames))
        }
    }

    @Test fun decoderRejectsErasedAndUnsupportedDate() {
        assertEquals(ZbnDecodedMetadata(), ZbnMetadataDecoder().decode(emptyList()))
        val bad = frames("24.00.26", "07:34:10")
        assertNull(ZbnMetadataDecoder().decode(bad).date)
    }

    @Test fun packedArinc573StreamMaintainsSubframeAndMetadataAlignment() {
        val expected = frames("24.09.26", "07:34:10")
        val packed = ByteArray(expected.size * 96)
        expected.forEachIndexed { frameIndex, frame ->
            frame.words.forEachIndexed { wordIndex, word ->
                for (bit in 0 until 12) {
                    val offset = (frameIndex * 64 + wordIndex) * 12 + bit
                    if (((word ushr (11 - bit)) and 1) != 0) {
                        val byteIndex = offset / 8
                        packed[byteIndex] = (packed[byteIndex].toInt() or
                            (1 shl (offset % 8))).toByte()
                    }
                }
            }
        }
        val parsed = Arinc717Parser().parsePackedPayload(packed)
        assertTrue(parsed.size >= expected.size - 1)
        assertEquals(ZbnDecodedMetadata("07:34:10", "24.09.26", "9339", "22466"),
            ZbnMetadataDecoder().decode(parsed))
    }

    private fun frames(date: String, time: String): List<Subframe> {
        val parts = date.split('.')
        val timeParts = time.split(':')
        val values = intArrayOf(
            bcd(parts[2].toInt()), bcd(parts[1].toInt()), bcd(parts[0].toInt()),
            bcd(66), bcd(24), 0xF2, bcd(39), bcd(93)
        )
        val syncs = intArrayOf(0x247, 0x5B8, 0xA47, 0xDB8)
        return (0 until 6).flatMap { major ->
            (0 until 4).map { sub ->
                val words = IntArray(64)
                words[0] = syncs[sub]
                val tag = (major % 2) * 4 + sub
                words[ZbnMetadataDecoder.IDENTIFICATION_WORD_INDEX] = reverse12(
                    (values[tag] and 15) or ((values[tag] ushr 4) shl 6) or
                        ((tag and 3) shl 4) or ((tag and 12) shl 8)
                )
                val timeValue = timeParts.getOrNull(
                    when (sub) { 0 -> 2; 1 -> 1; else -> 0 }
                )?.toIntOrNull() ?: 0
                words[ZbnMetadataDecoder.TIME_WORD_INDEX] = reverse12(bcdToSplit(timeValue))
                Subframe(sub + 1, words)
            }
        }
    }

    private fun bcd(n: Int): Int = (n / 10 shl 4) or (n % 10)
    private fun bcdToSplit(n: Int): Int = (n / 10 shl 6) or (n % 10)

    private fun reverse12(word: Int): Int {
        var result = 0
        for (bit in 0 until 12) result = (result shl 1) or ((word ushr bit) and 1)
        return result
    }
}
