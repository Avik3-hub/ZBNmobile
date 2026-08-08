package com.example.zbnreader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileNameFormatter {

    /**
     * Генерирует имя файла по шаблону: ГГГГММДД_номерВключения_НАГИБИН (без расширения)
     * 
     * @param record объект [FlightRecord] из вашего списка
     */
    fun makeFileName(record: FlightRecord): String {
        val formattedDate = parseAndFormatDate(record.date)
        return "${formattedDate}_${record.number}_НАГИБИН"
    }

    private fun parseAndFormatDate(dateStr: String): String {
        return try {
            // Парсим дату "ДД.ММ.ГГ" из FlightRecord.date (например, "07.08.26")
            val inputFormat = SimpleDateFormat("dd.MM.yy", Locale.US)
            val parsedDate = inputFormat.parse(dateStr.trim()) ?: Date()
            
            // Конвертируем в "ГГГГММДД" (например, "20260807")
            val outputFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
            outputFormat.format(parsedDate)
        } catch (e: Exception) {
            // Если дата некорректна, возвращаем текущую дату
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        }
    }
}
