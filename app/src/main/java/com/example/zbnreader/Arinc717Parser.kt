package com.example.zbnreader

/**
 * Структура одного субкадра (1 секунда полёта)
 * @param subframeIndex Номер субкадра (от 1 до 4)
 * @param words Массив 12-битных слов субкадра
 */
data class Subframe(
    val subframeIndex: Int,
    val words: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Subframe
        return subframeIndex == other.subframeIndex && words.contentEquals(other.words)
    }

    override fun hashCode(): Int {
        var result = subframeIndex
        result = 31 * result + words.contentHashCode()
        return result
    }
}

class Arinc717Parser(private val wordsPerSubframe: Int = 128) {

    companion object {
        // Синхрослова ARINC-717 / ARINC-573 (12 бит)
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

        // 1. Переводим байтовый поток в 12-битные слова (2 байта на слово, Little-Endian)
        val words = IntArray(rawData.size / 2) { i ->
            val b1 = rawData[i * 2].toInt() and 0xFF
            val b2 = rawData[i * 2 + 1].toInt() and 0xFF
            ((b2 shl 8) or b1) and 0x0FFF
        }

        var i = 0
        // 2. Поиск синхронизации с защитой от ложных срабатываний
        while (i <= words.size - wordsPerSubframe) {
            val candidateSync = words[i]
            val sfNum = getSubframeNumber(candidateSync)

            // Проверяем, что это не случайно совпавшее значение в полётных данных,
            // а реальная синхронизация (следующее синхрослово идет строго через wordsPerSubframe)
            if (sfNum != null && verifyNextSyncWord(words, i, sfNum)) {
                val subframeWords = words.copyOfRange(i, i + wordsPerSubframe)
                subframes.add(Subframe(sfNum, subframeWords))

                // Шагаем сразу на длину субкадра
                i += wordsPerSubframe
            } else {
                // Если сбой или ложная синхронизация — смещаемся на 1 слово
                i++
            }
        }

        return subframes
    }

    /**
     * Проверка правильности порядка следования субкадров (1 -> 2 -> 3 -> 4 -> 1)
     */
    private fun verifyNextSyncWord(words: IntArray, currentIndex: Int, currentSfNum: Int): Boolean {
        val nextIndex = currentIndex + wordsPerSubframe
        // Если дамп заканчивается, считаем текущее найденное слово валидным
        if (nextIndex >= words.size) return true

        val expectedNextSf = if (currentSfNum == 4) 1 else currentSfNum + 1
        val actualNextSync = words[nextIndex]

        return getSubframeNumber(actualNextSync) == expectedNextSf
    }

    private fun getSubframeNumber(word: Int): Int? = when (word) {
        SYNC_SF1 -> 1
        SYNC_SF2 -> 2
        SYNC_SF3 -> 3
        SYNC_SF4 -> 4
        else -> null
    }
}
