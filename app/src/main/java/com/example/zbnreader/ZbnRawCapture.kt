package com.example.zbnreader

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Keeps complete metadata-page responses, including transfer descriptors, without HEX logs. */
object ZbnRawCapture {
    const val DIRECTORY_NAME = "metadata_raw"

    fun save(directory: File, record: FlightRecord, address: Int, response: ByteArray): File {
        val prefix = "zbn_n${record.number}_b${record.memoryBank}_a${address.toString(16)}_"
        return saveBytes(directory, prefix, response)
    }

    fun saveCatalog(directory: File, catalog: ByteArray): File =
        saveBytes(directory, "zbn_catalog_", catalog)

    private fun saveBytes(directory: File, prefix: String, response: ByteArray): File {
        require(response.isNotEmpty()) { "Empty ZBN response" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Не удалось создать каталог диагностики: $directory")
        }
        val output = File.createTempFile(prefix, ".part", directory)
        val completed = File(directory, output.name.removeSuffix(".part") + ".bin")
        try {
            output.writeBytes(response)
            if (!output.renameTo(completed)) {
                throw IOException("Не удалось завершить файл диагностики: $output")
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
        return completed
    }

    /** Exports the text journal and every recorded raw metadata response together. */
    fun export(logFile: File, directory: File, destination: File): Int {
        val rawFiles = directory.listFiles()?.filter { it.isFile && it.extension == "bin" }
            ?.sortedBy { it.name }.orEmpty()
        require(logFile.isFile && logFile.length() > 0 || rawFiles.isNotEmpty()) {
            "Журнал и файлы диагностики пусты"
        }
        try {
            ZipOutputStream(FileOutputStream(destination)).use { zip ->
                if (logFile.isFile && logFile.length() > 0) {
                    zip.putNextEntry(ZipEntry("zbn_app_log.txt"))
                    logFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                for (raw in rawFiles) {
                    zip.putNextEntry(ZipEntry("metadata_raw/${raw.name}"))
                    raw.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
        return rawFiles.size
    }
}
