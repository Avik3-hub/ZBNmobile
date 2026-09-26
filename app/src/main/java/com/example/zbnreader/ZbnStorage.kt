package com.example.zbnreader

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ZbnStorage {
    enum class ChecklistStep(val key: String) {
        FLIGHT_DATA("flight_data"),
        EXCEL("excel"),
        PASSPORT("passport"),
        CLOUD("cloud")
    }

    const val ROOT_FOLDER_NAME = "ZBN files"

    fun rootFolder(): File = File(Environment.getExternalStorageDirectory(), ROOT_FOLDER_NAME)

    fun markStep(context: Context, tail: String, step: ChecklistStep) {
        context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            .edit()
            .putString(stepPreferenceKey(tail, step), todayStamp())
            .apply()
    }

    fun isStepComplete(context: Context, tail: String, step: ChecklistStep): Boolean {
        val today = todayStamp()
        val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        if (prefs.getString(stepPreferenceKey(tail, step), null) == today) return true

        val found = todayFiles(tail).any { file ->
            when (step) {
                ChecklistStep.FLIGHT_DATA ->
                    !file.extension.equals("jpg", true) &&
                        !file.extension.equals("xlsx", true) &&
                        !file.extension.equals("meta", true)
                ChecklistStep.EXCEL -> file.extension.equals("xlsx", true)
                ChecklistStep.PASSPORT -> file.extension.equals("jpg", true)
                ChecklistStep.CLOUD -> false
            }
        }

        if (found) markStep(context, tail, step)
        return found
    }

    fun todayFiles(tail: String): List<File> {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return aircraftFolder(tail).listFiles()
            ?.filter { it.isFile && it.lastModified() >= startOfToday }
            ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            .orEmpty()
    }

    private fun aircraftFolder(tail: String): File {
        val safeTail = DocumentFileName.normalizeTailNumber(tail).ifEmpty { "Неизвестный_Борт" }
        return File(rootFolder(), "Борт_$safeTail")
    }

    private fun stepPreferenceKey(tail: String, step: ChecklistStep): String {
        val safeTail = DocumentFileName.normalizeTailNumber(tail).ifEmpty { "unknown" }
        return "checklist_${step.key}_$safeTail"
    }

    private fun todayStamp(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
}
