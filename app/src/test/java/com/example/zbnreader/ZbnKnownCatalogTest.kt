package com.example.zbnreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic descriptors with expected values from the 24.09 PC catalog; no flight bytes. */
class ZbnKnownCatalogTest {
    private val known = listOf(
        Triple(95, 112128L, 0x175A0),
        Triple(94, 12288L, 0x17580),
        Triple(93, 79872L, 0x174E0),
        Triple(92, 123904L, 0x173E0),
        Triple(91, 27648L, 0x173A0),
        Triple(90, 37376L, 0x17340)
    )

    @Test fun catalogCountsAndAddressesMatchKnownFlightList() {
        val descriptors = ArrayList<ByteArray>()
        for ((number, size, start) in known) {
            repeat((size / 512L).toInt()) { index ->
                descriptors.add(descriptor(number, start + index, bank = 2))
            }
        }
        descriptors.add(ByteArray(16) { 0xFF.toByte() })
        descriptors.add(descriptor(65534, 0x17500, bank = 2)) // service record
        val records = ZbnTocParser().parse(descriptors.fold(ByteArray(0)) { all, d -> all + d })
        assertEquals(known.size, records.size)
        val durations = mapOf(
            95 to "00:19:28", 94 to "00:02:08", 93 to "00:13:52",
            92 to "00:21:30", 91 to "00:04:48", 90 to "00:06:29"
        )
        for ((number, size, start) in known) {
            val record = records.single { it.number == number }
            assertEquals(size, record.sizeBytes)
            assertEquals(start, record.startAddress)
            assertEquals(2, record.memoryBank)
            assertEquals(durations[number], record.duration)
            assertEquals("—", record.date)
        }
    }

    @Test fun erasedGarbageAndWrongBankDoNotBecomeFlights() {
        val noise = ByteArray(16) { 0xFF.toByte() }
        noise[0] = 0x23
        val data = descriptor(95, 0x175A0, 2) +
            ByteArray(16) { 0xFF.toByte() } + noise +
            descriptor(95, 0x175A1, 1) + descriptor(96, 0x175A2, 0)
        val records = ZbnTocParser().parse(data)
        assertEquals(1, records.size)
        assertEquals(512L, records.single().sizeBytes)
    }

    @Test fun selectedRecordExtractsOnlyItsOwnSectors() {
        val raw = ByteArray(16896) { 0xFF.toByte() }
        fun addSector(block: Int, number: Int, fill: Byte) {
            val offset = block * 528
            raw.fill(fill, offset, offset + 512)
            descriptor(number, 0x175A0 + block, 2).copyInto(raw, offset + 512)
        }
        addSector(0, 95, 0x11)
        addSector(1, 94, 0x22)
        addSector(2, 95, 0x33)
        val payload = ZbnMetadataReader.extractRecordPayload(raw, 95, 0x175A0)
        assertEquals(1024, payload.size)
        assertTrue(payload.sliceArray(0 until 512).all { it == 0x11.toByte() })
        assertTrue(payload.sliceArray(512 until 1024).all { it == 0x33.toByte() })
        assertFalse(payload.any { it == 0x22.toByte() })
        assertThrows(IllegalArgumentException::class.java) {
            ZbnMetadataReader.extractRecordPayload(raw, 95, 0x17580)
        }
    }

    private fun descriptor(number: Int, address: Int, bank: Int): ByteArray =
        ByteArray(16).apply {
            this[0] = number.toByte()
            this[1] = (number ushr 8).toByte()
            this[2] = address.toByte()
            this[3] = (address ushr 8).toByte()
            this[4] = (address ushr 16).toByte()
            this[6] = bank.toByte()
            this[12] = 0x55
            this[13] = 0xAA.toByte()
        }
}
