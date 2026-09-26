package com.example.zbnreader

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ZbnStorage {
    const val ROOT_FOLDER_NAME = "ZBN files"
    private const val PREF_LAST_PASSPORT_DATE = "last_saved_passport_date"

    fun rootFolder(): File = File(Environment.getExternalStorageDirectory(), ROOT_FOLDER_NAME)

    fun markPassportSaved(context: Context) {
        context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_PASSPORT_DATE, todayStamp())
            .apply()
    }

    fun hasPassportSavedToday(context: Context): Boolean {
        val today = todayStamp()
        val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        if (prefs.getString(PREF_LAST_PASSPORT_DATE, null) == today) return true

        val prefix = "${today}_"
        val found = rootFolder().listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith("Борт_") }
            ?.flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            ?.any { it.isFile && it.name.startsWith(prefix) && it.extension.equals("jpg", true) }
            ?: false

        if (found) markPassportSaved(context)
        return found
    }

    private fun todayStamp(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
}
