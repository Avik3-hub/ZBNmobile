package com.example.zbnreader

data class ZbnDecodedMetadata(
    val startTime: String? = null,
    val date: String? = null,
    val flightNum: String? = null,
    val tailNum: String? = null
)

/** Decodes BUR-1/ZBN metadata carried in the ARINC-573 subframes. */
class ZbnMetadataDecoder(
    private val arincParser: Arinc717Parser = Arinc717Parser()
) {

    fun decode(rawTransferData: ByteArray): ZbnDecodedMetadata =
        decode(arincParser.parseRawBytes(rawTransferData))

    fun decode(subframes: List<Subframe>): ZbnDecodedMetadata {
        // Require repeated agreement, not just one coincidental bit pattern.
        val votes = Array(12) { mutableMapOf<Int, Int>() }
        for (frame in subframes) {
            val word = frame.words.getOrNull(IDENTIFICATION_WORD_INDEX) ?: continue
            val value = reverse12Bits(word)
            val tag = ((value ushr 4) and 3) or ((value ushr 8) and 12)
            if (tag !in 0..11 || frame.subframeIndex != tag % 4 + 1) continue
            val digits = (((value ushr 6) and 15) shl 4) or (value and 15)
            votes[tag][digits] = (votes[tag][digits] ?: 0) + 1
        }
        val values = votes.map { counts ->
            val best = counts.maxByOrNull { it.value }
            if (best != null && best.value >= 2 && best.value * 5 >= counts.values.sum() * 4)
                best.key else null
        }
        fun decimal(tag: Int): Int? {
            val value = values[tag] ?: return null
            val tens = value ushr 4
            val units = value and 15
            return if (tens <= 9 && units <= 9) tens * 10 + units else null
        }
        val year = decimal(0)
        val month = decimal(1)
        val day = decimal(2)
        val date = if (year != null && month != null && day != null &&
            validDate(2000 + year, month, day))
            "${twoDigits(day)}.${twoDigits(month)}.${twoDigits(year)}" else null
        val tailLow = decimal(3)
        val tailMiddle = decimal(4)
        val tailHigh = values[5]?.and(15)
        // Tag 5's upper digit is F in this capture; it is padding, not '15'.
        val tail = if (tailLow != null && tailMiddle != null && tailHigh != null &&
            tailHigh in 0..9 && values[5]?.ushr(4) == 15)
            "$tailHigh${twoDigits(tailMiddle)}${twoDigits(tailLow)}" else null
        val flightLow = decimal(6)
        val flightHigh = decimal(7)
        val flight = if (flightLow != null && flightHigh != null)
            "${twoDigits(flightHigh)}${twoDigits(flightLow)}" else null
        return ZbnDecodedMetadata(decodeStartTime(subframes), date, flight, tail)
    }

    private fun validDate(year: Int, month: Int, day: Int): Boolean {
        if (month !in 1..12) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return day in 1..days[month - 1]
    }

    private fun decodeStartTime(subframes: List<Subframe>): String? {
        // Use one complete major-frame fragment so seconds, minutes and hours
        // belong to the same timestamp even if the transfer starts at SF2..SF4.
        for (index in 0..subframes.size - 3) {
            if (subframes[index].subframeIndex != 1 ||
                subframes[index + 1].subframeIndex != 2 ||
                subframes[index + 2].subframeIndex != 3
            ) {
                continue
            }

            val seconds = decodeSplitBcd(subframes[index].words.getOrNull(TIME_WORD_INDEX))
            val minutes = decodeSplitBcd(subframes[index + 1].words.getOrNull(TIME_WORD_INDEX))
            val hours = decodeSplitBcd(subframes[index + 2].words.getOrNull(TIME_WORD_INDEX))

            if (hours in 0..23 && minutes in 0..59 && seconds in 0..59) {
                return "${twoDigits(hours)}:${twoDigits(minutes)}:${twoDigits(seconds)}"
            }
        }
        return null
    }

    /**
     * BUR-1 stores the decimal tens digit in bits 6..9 and the units digit in
     * bits 0..3 after the 12 transmitted bits have been reversed.
     */
    private fun decodeSplitBcd(word: Int?): Int {
        if (word == null) return -1
        val reversed = reverse12Bits(word)
        val tens = (reversed ushr 6) and 0x0F
        val units = reversed and 0x0F
        if (tens > 9 || units > 9) return -1
        return tens * 10 + units
    }

    private fun reverse12Bits(value: Int): Int {
        var source = value and 0x0FFF
        var reversed = 0
        repeat(12) {
            reversed = (reversed shl 1) or (source and 1)
            source = source ushr 1
        }
        return reversed
    }

    private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

    companion object {
        // Zero-based index. In human-readable ARINC numbering this is word 37.
        const val TIME_WORD_INDEX = 36
        const val IDENTIFICATION_WORD_INDEX = 32
    }
}
