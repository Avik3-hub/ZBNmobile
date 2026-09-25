package com.example.zbnreader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DocumentFileName {
    const val PREF_LAST_TAIL = "last_tail_number"
    const val PREF_SURNAME = "document_surname"
    const val PREF_MEGAPIXELS = "document_megapixels"

    fun normalizeTailNumber(value: String): String = value
        .trim()
        .replace(Regex("(?i)^RA[-_ ]*"), "")
        .filter(Char::isDigit)

    fun normalizeSurname(value: String): String = value
        .trim()
        .uppercase(Locale.getDefault())
        .replace(Regex("[^A-ZА-ЯЁ0-9_-]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')

    fun create(tailNumber: String, surname: String, date: Date = Date()): String {
        val datePart = SimpleDateFormat("yyyyMMdd", Locale.US).format(date)
        val tailPart = normalizeTailNumber(tailNumber).ifEmpty { "БОРТ" }
        val surnamePart = normalizeSurname(surname).ifEmpty { "ФАМИЛИЯ" }
        return "${datePart}_${tailPart}_${surnamePart}.jpg"
    }

    fun normalizeCustomFileName(value: String, fallback: String): String {
        val clean = value
            .trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1F<>:\"/\\\\|?*]"), "_")
            .replace(Regex("_+"), "_")
            .trim(' ', '.')
            .take(120)
        if (clean.isEmpty()) return fallback
        return if (clean.endsWith(".jpg", ignoreCase = true)) {
            clean.dropLast(4) + ".jpg"
        } else {
            clean.substringBeforeLast('.', clean) + ".jpg"
        }
    }
}
