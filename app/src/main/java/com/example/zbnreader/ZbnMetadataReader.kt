package com.example.zbnreader

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Read-only metadata-page transaction observed in the supplied capture. */
class ZbnMetadataReader(private val port: UsbSerialPort) {
    fun read(record: FlightRecord): ZbnDecodedMetadata {
        val address = record.startAddress
        require(address in 0..0xFFFFE0 && address and 31 == 0)
        // A fresh ENQ/ACK starts each page transaction. Never scan arbitrary
        // residual bytes for ACK: a stale catalog is not an acknowledgement.
        port.write(byteArrayOf(0x05), 1000)
        // FTDI needs room for USB packets, even when only one payload byte
        // is expected. Validate the returned length, not the buffer capacity.
        val ack = ByteArray(4096)
        val ackCount = port.read(ack, 2000)
        if (ackCount != 1 || ack[0] != 0x06.toByte())
            throw IOException("ЗБН не подтвердил запрос страницы (ACK: $ackCount байт)")
        val command = byteArrayOf(0x13, 0x02, address.toByte(),
            (address ushr 8).toByte(), (address ushr 16).toByte(), 0x02,
            address.toByte(), (address ushr 8).toByte(),
            (address ushr 16).toByte(), 0x02)
        port.write(command, 1000)
        val response = ByteArrayOutputStream()
        // Different ZBN-1-3 revisions return either a complete 32-block
        // transport page or a shorter raw ARINC stream terminated by silence.
        val chunk = ByteArray(4096)
        val chunkSizes = mutableListOf<Int>()
        var emptyReads = 0
        val deadline = System.nanoTime() + 15_000_000_000L
        while (response.size() < FIXED_PAGE_SIZE) {
            if (System.nanoTime() >= deadline) {
                if (response.size() > 0) break
                throw IOException(
                    describePartialResponse(
                        response.toByteArray(),
                        address,
                        chunkSizes,
                        FIXED_PAGE_SIZE
                    )
                )
            }
            val count = port.read(chunk, 1000)
            if (count > 0) {
                if (response.size() + count > FIXED_PAGE_SIZE)
                    throw IOException(
                        "Лишние данные страницы: принято ${response.size()} + $count, " +
                            "максимум $FIXED_PAGE_SIZE"
                    )
                response.write(chunk, 0, count)
                chunkSizes.add(count)
                emptyReads = 0
            } else if (response.size() > 0) {
                emptyReads++
                if (emptyReads >= END_OF_RESPONSE_EMPTY_READS) break
            }
        }
        val raw = response.toByteArray()
        // Observed end-of-transfer sequence. Short raw replies also need it:
        // otherwise the next ENQ can arrive while the ZBN is still in transfer.
        port.write(byteArrayOf(0x06, 0x05, 0x06), 1000)
        return if (raw.size == FIXED_PAGE_SIZE) {
            decodePage(raw, record.number, address)
        } else {
            // A short reply has no 16-byte transport descriptors. The decoder
            // searches the bit-packed ARINC-573 stream for valid subframes.
            ZbnMetadataDecoder().decode(raw)
        }
    }

    private fun describePartialResponse(
        raw: ByteArray,
        address: Int,
        chunkSizes: List<Int>,
        expected: Int
    ): String {
        val received = raw.size
        val headEnd = minOf(received, DIAGNOSTIC_PREVIEW_BYTES)
        val tailStart = maxOf(0, received - DIAGNOSTIC_PREVIEW_BYTES)
        val head = raw.toHex(0, headEnd)
        val tail = raw.toHex(tailStart, received)
        val formattedAddress = address.toString(16).uppercase().padStart(6, '0')
        return "Таймаут страницы: $received/$expected байт; " +
            "адрес=0x$formattedAddress; порции=$chunkSizes; " +
            "начало=[$head]; конец=[$tail]"
    }

    private fun ByteArray.toHex(start: Int, end: Int): String =
        (start until end).joinToString(" ") {
            (this[it].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }

    companion object {
        private const val DIAGNOSTIC_PREVIEW_BYTES = 64
        private const val FIXED_PAGE_SIZE = 16896
        private const val END_OF_RESPONSE_EMPTY_READS = 3

        fun decodePage(raw: ByteArray, recordNumber: Int, address: Int): ZbnDecodedMetadata {
            require(raw.size == FIXED_PAGE_SIZE) { "Неполная страница ЗБН" }
            val payload = ByteArrayOutputStream()
            var started = false
            var ended = false
            repeat(32) { block ->
                val offset = block * 528
                val d = offset + 512
                val erased = (d until d + 16).all { raw[it] == 0xFF.toByte() }
                if (erased) {
                    if (started) ended = true
                } else {
                    require(raw[d + 12] == 0x55.toByte() && raw[d + 13] == 0xAA.toByte()) {
                        "Поврежден дескриптор страницы"
                    }
                    fun u(i: Int) = raw[d + i].toInt() and 255
                    val actualAddress = u(2) or (u(3) shl 8) or (u(4) shl 16)
                    require(actualAddress == address + block) { "Неверный адрес ответа ЗБН" }
                    val number = u(0) or (u(1) shl 8)
                    if (number == recordNumber) {
                        require(!ended) { "Разрыв данных включения внутри страницы" }
                        payload.write(raw, offset, 512)
                        started = true
                    } else if (started) ended = true
                }
            }
            require(started) { "В странице нет выбранного включения" }
            // Already-stripped payload must not pass transport auto-detection.
            return ZbnMetadataDecoder().decode(Arinc717Parser().parsePackedPayload(payload.toByteArray()))
        }
    }
}
