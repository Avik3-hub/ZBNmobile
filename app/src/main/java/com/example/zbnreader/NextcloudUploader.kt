package com.example.zbnreader

import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NextcloudUploader(private val settings: NextcloudSettings) {
    data class Result(
        val uploaded: Int,
        val total: Int,
        val error: String? = null,
        val destination: String? = null
    ) {
        val successful: Boolean
            get() = error == null && destination != null && uploaded == total && total > 0
    }

    private val authorization = "Basic " + Base64.encodeToString(
        "${settings.username}:${settings.appPassword}".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP
    )

    fun upload(files: List<File>, tail: String, onProgress: (Int, Int, String) -> Unit): Result {
        if (files.isEmpty()) return Result(0, 0, "За сегодня нет файлов для отправки")
        var uploaded = 0
        return try {
            val aircraftType = aircraftTypeFor(tail)
            val destination = "$aircraftType/$tail"
            val boardUrl = "${webDavRoot()}/${segment(aircraftType)}/${segment(tail)}/"

            files.forEachIndexed { index, file ->
                onProgress(index, files.size, file.name)
                uploadFile("$boardUrl${segment(file.name)}", file, destination)
                uploaded++
                onProgress(uploaded, files.size, file.name)
            }
            Result(uploaded, files.size, destination = destination)
        } catch (error: Exception) {
            Result(uploaded, files.size, readableError(error))
        }
    }

    private fun aircraftTypeFor(tail: String): String = when (tail) {
        in MI8_T_BOARDS -> "Ми-8 Т"
        in MI8_AMT_BOARDS -> "Ми-8 АМТ"
        else -> settings.aircraftType
    }

    private fun uploadFile(url: String, file: File, destination: String) {
        val connection = open(url, "PUT").apply {
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            file.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            }
            val code = connection.responseCode
            when {
                code in 200..299 -> Unit
                code == HttpURLConnection.HTTP_CONFLICT ||
                    code == HttpURLConnection.HTTP_NOT_FOUND -> throw MissingFolderException(destination)
                else -> error("${file.name}: сервер вернул $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 90_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Authorization", authorization)
        }

    private fun webDavRoot(): String =
        settings.baseUrl.trimEnd('/') +
            "/remote.php/dav/files/" + segment(settings.username)

    private fun segment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun readableError(error: Exception): String {
        val message = error.localizedMessage.orEmpty()
        return when {
            error is MissingFolderException ->
                "Папка «${error.destination}» не найдена в облаке. " +
                    "Файлы не отправлены, папки приложение не создавало."
            message.contains("401") || message.contains("403") ->
                "Nextcloud отклонил имя пользователя или пароль приложения"
            message.contains("certificate", ignoreCase = true) ||
                message.contains("SSL", ignoreCase = true) ->
                "Ошибка сертификата HTTPS. Проверьте адрес сервера и сертификат"
            else -> message.ifBlank { "Не удалось отправить файлы" }
        }
    }

    private class MissingFolderException(val destination: String) : Exception()

    companion object {
        private val MI8_T_BOARDS = setOf("06105", "22963", "24129", "24594")
        private val MI8_AMT_BOARDS = setOf(
            "22232", "22271", "22272", "22436", "22454", "22459",
            "22462", "22464", "22465", "22466", "22468", "22469",
            "22967", "25325"
        )
    }
}
