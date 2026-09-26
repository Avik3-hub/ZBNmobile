package com.example.zbnreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class ZbnRawCaptureTest {
    @Test fun savesFullAndPartialResponsesAndExportsBothWithJournal() {
        val root = Files.createTempDirectory("zbn-diagnostics-test").toFile()
        try {
            val rawDir = root.resolve(ZbnRawCapture.DIRECTORY_NAME)
            val record = FlightRecord(95, 112128, "—", "00:19:28", "—", "—", "—", "—",
                0x175A0, 0x175A0, 2)
            val full = ByteArray(16896) { (it % 251).toByte() }
            val partial = byteArrayOf(0x05, 0x06, 0x55, 0xAA.toByte())
            val first = ZbnRawCapture.save(rawDir, record, record.startAddress, full)
            val second = ZbnRawCapture.save(rawDir, record, record.startAddress, partial)
            val catalog = ZbnRawCapture.saveCatalog(rawDir, byteArrayOf(0x55, 0xAA.toByte()))
            assertTrue(first != second)
            assertArrayEquals(full, first.readBytes())
            assertArrayEquals(partial, second.readBytes())

            val journal = root.resolve("zbn_app_log.txt")
            journal.writeText("META_RX №95: bytes=16896/16896\n")
            val archive = root.resolve("diagnostics.zip")
            assertEquals(3, ZbnRawCapture.export(journal, rawDir, archive))
            ZipFile(archive).use { zip ->
                assertArrayEquals(journal.readBytes(),
                    zip.getInputStream(zip.getEntry("zbn_app_log.txt")).use { it.readBytes() })
                for ((file, expected) in listOf(first to full, second to partial)) {
                    val entry = zip.getEntry("metadata_raw/${file.name}")
                    assertArrayEquals(expected, zip.getInputStream(entry).use { it.readBytes() })
                }
                assertArrayEquals(catalog.readBytes(),
                    zip.getInputStream(zip.getEntry("metadata_raw/${catalog.name}"))
                        .use { it.readBytes() })
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
