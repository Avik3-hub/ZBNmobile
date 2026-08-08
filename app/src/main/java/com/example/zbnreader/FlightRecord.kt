package com.example.zbnreader

data class FlightRecord(
    val number: Int,         // № включения (1033, 1032...)
    val sizeBytes: Long,     // Размер в байтах
    val date: String,        // Дата (07.08.26)
    val duration: String,    // Время/Продолжительность (00:08:48)
    val startTime: String,   // Начало (10:09:46)
    val endTime: String,     // Конец (12:10:01)
    val flightNum: String,   // Рейс (9336)
    val tailNum: String      // Борт (22469)
)

