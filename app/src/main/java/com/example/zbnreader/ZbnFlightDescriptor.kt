package com.example.zbnreader

/**
 * Описание сектора/блока оглавления ЗБН
 */
data class ZbnFlightDescriptor(
    val sectorIndex: Int,
    val type: Int,
    val startAddress: Int,
    val metadataRaw: ByteArray,
    val subBlockIndex: Int,
    val isValidCrc: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as ZbnFlightDescriptor
        return startAddress == other.startAddress && sectorIndex == other.sectorIndex
    }

    override fun hashCode(): Int {
        var result = startAddress
        result = 31 * result + sectorIndex
        return result
    }
}
