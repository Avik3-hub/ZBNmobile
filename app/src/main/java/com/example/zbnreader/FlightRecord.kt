package com.example.zbnreader

data class FlightRecord(
    val number: Int,
    val sizeBytes: Long,
    val date: String,
    val duration: String,
    val startTime: String,
    val endTime: String,
    val flightNum: String,
    val tailNum: String,
    val startAddress: Int = -1,
    val endAddress: Int = -1,
    /** Memory bank copied from byte 6 of the ZBN catalog descriptor. */
    val memoryBank: Int = 0x02
)
