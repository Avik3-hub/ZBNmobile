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
        val ack = ByteArray(1)
        if (port.read(ack, 2000) != 1 || ack[0] != 0x06.toByte())
            throw IOException("ЗБН не подтвердил запрос страницы")
        val command = byteArrayOf(0x13, 0x02, address.toByte(),
            (address ushr 8).toByte(), (address ushr 16).toByte(), 0x02,
            address.toByte(), (address ushr 8).toByte(),
            (address ushr 16).toByte(), 0x02)
        port.write(command, 1000)
        val raw = ByteArray(16896)
        var received = 0
        val deadline = System.nanoTime() + 15_000_000_000L
        while (received < raw.size) {
            if (System.nanoTime() >= deadline)
                throw IOException("Таймаут страницы: $received/${raw.size} байт")
            val chunk = ByteArray(minOf(4096, raw.size - received))
            val count = port.read(chunk, 1000)
            if (count > 0) {
                chunk.copyInto(raw, received, 0, count)
                received += count
            }
        }
        // Observed end-of-transfer sequence; sent only after the full response.
        port.write(byteArrayOf(0x06, 0x05, 0x06), 1000)
        return decodePage(raw, record.number, address)
    }

    companion object {
        fun decodePage(raw: ByteArray, recordNumber: Int, address: Int): ZbnDecodedMetadata {
            require(raw.size == 16896) { "Неполная страница ЗБН" }
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
