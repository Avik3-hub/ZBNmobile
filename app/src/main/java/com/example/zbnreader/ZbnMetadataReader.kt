package com.example.zbnreader

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Read-only metadata-page transaction observed in the supplied capture. */
class ZbnMetadataReader(
    private val port: UsbSerialPort,
    private val catalogBaudRate: Int = 115200,
    private val metadataBaudRate: Int = 921600,
    private val rawPageDiagnostic: (FlightRecord, Int, ByteArray) -> Unit = { _, _, _ -> },
    private val diagnostic: (String) -> Unit = {}
) {
    fun read(record: FlightRecord): ZbnDecodedMetadata {
        val raw = readRawPage(record, record.startAddress)
        return if (raw.size == FIXED_PAGE_SIZE) {
            decodePage(raw, record.number, record.startAddress)
        } else {
            val frames = Arinc717Parser().parseRawBytes(raw)
            diagnostic("META_SHORT №${record.number}: ARINC subframes=${frames.size}; " +
                "формат и принадлежность ответа не подтверждены, подписи не используются")
            ZbnDecodedMetadata()
        }
    }

    /** Reads one 32-sector page without changing anything in ZBN memory. */
    fun readRawPage(record: FlightRecord, address: Int): ByteArray {
        require(address in 0..0xFFFFE0 && address and 31 == 0)
        require(record.memoryBank in 1..2) { "Неизвестный банк памяти ЗБН" }

        // The reference PC program performs ENQ/ACK and sends the page command
        // at the catalog speed. Only the 16896-byte response uses high speed.
        diagnostic(
            "META_TX №${record.number}: handshake=$catalogBaudRate, " +
                "address=0x${address.toString(16)}, bank=${record.memoryBank}"
        )
        try {
            // A fresh ENQ/ACK starts each page transaction. Never scan arbitrary
            // residual bytes for ACK: a stale catalog is not an acknowledgement.
            port.write(byteArrayOf(0x05), 1000)
            // FTDI needs room for USB packets, even when only one payload byte
            // is expected. Validate the returned length, not the buffer capacity.
            val ack = ByteArray(4096)
            val ackCount = port.read(ack, 2000)
            if (ackCount != 1 || ack[0] != 0x06.toByte())
                throw IOException("ЗБН не подтвердил запрос страницы (ACK: $ackCount байт)")
            val bank = record.memoryBank.toByte()
            val command = byteArrayOf(0x13, 0x02, address.toByte(),
                (address ushr 8).toByte(), (address ushr 16).toByte(), bank,
                address.toByte(), (address ushr 8).toByte(),
                (address ushr 16).toByte(), bank)
            // PCAP: ACK -> 94 ms -> command -> 31 ms -> high baud.
            Thread.sleep(90)
            port.write(command, 1000)
            Thread.sleep(30)
            setBaudRate(metadataBaudRate)
            diagnostic("META_RX №${record.number}: скорость приёма $metadataBaudRate бод")
            return readPage(record, address)
        } finally {
            try {
                setBaudRate(catalogBaudRate)
                diagnostic("META_PORT: скорость возвращена на $catalogBaudRate бод")
            } catch (e: Exception) {
                diagnostic("META_PORT: не удалось вернуть $catalogBaudRate бод: ${e.message}")
            }
        }
    }

    private fun readPage(record: FlightRecord, address: Int): ByteArray {
        val response = ByteArrayOutputStream()
        // Short responses are unverified: silence may also mean lost bytes.
        val chunk = ByteArray(4096)
        val chunkSizes = mutableListOf<Int>()
        var emptyReads = 0
        val deadline = System.nanoTime() + 15_000_000_000L
        try {
            while (response.size() < FIXED_PAGE_SIZE) {
                if (System.nanoTime() >= deadline) {
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
                    if (response.size() + count > FIXED_PAGE_SIZE) {
                        response.write(chunk, 0, count)
                        throw IOException(
                            "Лишние данные страницы: принято ${response.size()} байт, " +
                                "максимум $FIXED_PAGE_SIZE"
                        )
                    }
                    response.write(chunk, 0, count)
                    chunkSizes.add(count)
                    emptyReads = 0
                } else if (response.size() > 0) {
                    emptyReads++
                    if (emptyReads >= END_OF_RESPONSE_EMPTY_READS) break
                }
            }
        } catch (e: Exception) {
            captureRaw(record, address, response.toByteArray())
            throw e
        }
        val raw = response.toByteArray()
        // Observed end-of-transfer sequence. Short raw replies also need it:
        // otherwise the next ENQ can arrive while the ZBN is still in transfer.
        // PCAP switches back to 115200 before this sequence and its final ACK.
        try {
            setBaudRate(catalogBaudRate)
            port.write(byteArrayOf(0x06, 0x05, 0x06), 1000)
            val finalAck = ByteArray(4096)
            val finalAckCount = port.read(finalAck, 1000)
            if (finalAckCount != 1 || finalAck[0] != 0x06.toByte()) {
                diagnostic("META_END №${record.number}: финальный ACK не получен ($finalAckCount байт)")
            }
        } finally {
            // Persist after receiving the data even if the closing ACK fails.
            captureRaw(record, address, raw)
        }
        // The complete response is in a binary file; text remains readable.
        diagnostic("META_RX №${record.number}: address=0x${address.toString(16)}, " +
            "bytes=${raw.size}/$FIXED_PAGE_SIZE, chunks=${chunkSizes.size}")
        return raw
    }

    private fun captureRaw(record: FlightRecord, address: Int, raw: ByteArray) {
        if (raw.isEmpty()) return
        try {
            rawPageDiagnostic(record, address, raw)
        } catch (e: Exception) {
            diagnostic("META_RAW №${record.number}: не удалось сохранить ${raw.size} байт: ${e.message}")
        }
    }

    private fun setBaudRate(baudRate: Int) {
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.dtr = false
        port.rts = false
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
            val payload = extractRecordPayload(raw, recordNumber, address)
            require(payload.isNotEmpty()) { "В странице нет выбранного включения" }
            // Already-stripped payload must not pass transport auto-detection.
            return ZbnMetadataDecoder().decode(Arinc717Parser().parsePackedPayload(payload))
        }

        /** Returns only 512-byte data sectors belonging to the selected record. */
        fun extractRecordPayload(
            raw: ByteArray,
            recordNumber: Int,
            address: Int
        ): ByteArray {
            require(raw.size == FIXED_PAGE_SIZE) { "Неполная страница ЗБН" }
            val payload = ByteArrayOutputStream()
            repeat(32) { block ->
                val offset = block * 528
                val d = offset + 512
                val erased = (d until d + 16).all { raw[it] == 0xFF.toByte() }
                if (!erased) {
                    require(raw[d + 12] == 0x55.toByte() && raw[d + 13] == 0xAA.toByte()) {
                        "Поврежден дескриптор страницы"
                    }
                    fun u(i: Int) = raw[d + i].toInt() and 255
                    val actualAddress = u(2) or (u(3) shl 8) or (u(4) shl 16)
                    require(actualAddress == address + block) { "Неверный адрес ответа ЗБН" }
                    val number = u(0) or (u(1) shl 8)
                    if (number == recordNumber) {
                        payload.write(raw, offset, 512)
                    }
                }
            }
            return payload.toByteArray()
        }
    }
}
