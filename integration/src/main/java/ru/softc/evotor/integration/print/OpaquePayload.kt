package ru.softc.evotor.integration.print

import android.os.Parcel

/**
 * Непрозрачный payload структурированного документа.
 *
 * Появляется только у документа, прочитанного из Parcel с версией протокола,
 * которую библиотека не поддерживает: [StructuredDocument.PROTOCOL_VERSION_CURRENT]
 * отличается от [StructuredDocument.protocolVersion]. Поля такого документа
 * остаются пустыми - библиотека не разбирает чужой layout, - но сам payload
 * сохраняется и переписывается при пересылке без изменений.
 *
 * Требование к wire-format: версия и длина payload читаются получателем раньше
 * полей, поэтому payload неизвестной версии обязан пересылаться без изменения.
 * Иначе промежуточное приложение подменит документ пустым - получатель с
 * подходящей версией напечатает пустой чек вместо исходного.
 *
 * Payload хранится словами по 4 байта: позиция данных [Parcel] выравнивается
 * по 4 байта всеми примитивами, поэтому длина payload конверта обязана быть
 * кратна 4 - иначе границы payload не попадают на границу слова и побайтовое
 * сохранение невозможно.
 *
 * Экземпляры создаются только библиотекой (внутренний конструктор): снаружи
 * непрозрачный payload нельзя подделать или подменить.
 */
class OpaquePayload internal constructor(private val words: IntArray) {

    /**
     * Размер payload в байтах - ровно то значение, которое было прочитано
     * из конверта версии.
     */
    val size: Int get() = words.size * WORD_SIZE

    /**
     * Переписывает payload в Parcel без изменения: получатель должен получить
     * ту же последовательность байт, что отправил исходный писатель.
     */
    internal fun writeTo(dest: Parcel) {
        for (word in words) {
            dest.writeInt(word)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is OpaquePayload && words.contentEquals(other.words)

    override fun hashCode(): Int = words.contentHashCode()

    override fun toString(): String = "OpaquePayload(size=$size)"

    companion object {

        /**
         * Размер слова, которым выровнен payload в [Parcel].
         */
        const val WORD_SIZE = 4

        /**
         * Читает ровно [length] байт payload из [source] и запоминает их.
         *
         * Читаются слова целиком: чтение по одному байту невозможно - позиция
         * данных [Parcel] выравнивается по 4 байта, поэтому границы payload
         * обязаны совпадать с границами слов.
         */
        internal fun read(source: Parcel, length: Int): OpaquePayload {
            val words = IntArray(length / WORD_SIZE)
            for (index in words.indices) {
                words[index] = source.readInt()
            }
            return OpaquePayload(words)
        }
    }
}
