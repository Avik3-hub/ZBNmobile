package com.example.zbnreader

/**
 * Структура одного субкадра (1 секунда полёта)
 * @param subframeIndex Номер субкадра (от 1 до 4)
 * @param words Массив 12-битных слов субкадра
 */
data class Subframe(
    val subframeIndex: Int,
    val words: IntArray
)

class Arinc717Parser(private val wordsPerSubframe: Int = 128) {

    companion object {
        // Синхрослова ARINC-717 (12 бит)
        const val SYNC_SF1 = 0x247
        const val SYNC_SF2 = 0x5B8
        const val SYNC_SF3 = 0xA47
        const val SYNC_SF4 = 0xDB8
    }

    /**
     * Поиск синхронизации и нарезка бинарного дампа на субкадры
     */
    fun parseRawBytes(rawData: ByteArray): List<Subframe> {
        val subframes = mutableListOf<Subframe>()

        // 1. Переводим байтовый поток в 12-битные слова (2 байта на слово)
        val words = IntArray(rawData.size / 2) { i ->
            val b1 = rawData[i * 2].toInt() and 0xFF
            val b2 = rawData[i * 2 + 1].toInt() and 0xFF
            // Извлекаем 12 бит (значение от 0 до 4095)
            ((b2 shl 8) or b1) and 0x0FFF
        }

        var i = 0
        // 2. Ищем синхрослово и собираем кадры
        while (i <= words.size - wordsPerSubframe) {
            val candidateSync = words[i]
            val sfNum = getSubframeNumber(candidateSync)

            if (sfNum != null) {
                // Синхронизация найдена! Вырезаем полный субкадр
                val subframeWords = words.copyOfRange(i, i + wordsPerSubframe)
                subframes.add(Subframe(sfNum, subframeWords))

                // Шагаем сразу на длину субкадра
                i += wordsPerSubframe
            } else {
                // Если синхрослова нет, смещаемся на 1 слово вперед
                i++
            }
        }

        return subframes
    }

    private fun getSubframeNumber(word: Int): Int? = when (word) {
        SYNC_SF1 -> 1
        SYNC_SF2 -> 2
        SYNC_SF3 -> 3
        SYNC_SF4 -> 4
        else -> null
    }
}

