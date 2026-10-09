package ru.softc.evotor.integration.print

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable

/**
 * Структурированный печатный документ: контракт между интеграционным приложением
 * и получателем печати (serviceprint / драйвер принтера).
 *
 * Реализация Parcelable ручная (N1): плагин kotlin-parcelize в библиотеке не подключен.
 * Порядок полей в [writeToParcel] и чтении в [CREATOR] обязан оставаться синхронным.
 *
 * ## Версия протокола: нормативное правило получателя
 *
 * [protocolVersion] - версия wire-layout этого контракта, а не версия библиотеки.
 * Поддерживаемая версия публикуется как [PROTOCOL_VERSION_CURRENT].
 *
 * Правило получателя (T3/T4, вне библиотеки):
 * **если `protocolVersion != PROTOCOL_VERSION_CURRENT` - документ отклоняется,
 * печать не выполняется, вызывающему приложению возвращается явная ошибка
 * неподдерживаемой версии протокола.** Разбор полей при несовпадающей версии запрещён.
 *
 * Обоснование: версия определяет порядок и набор полей в Parcel. Получатель, который
 * разберёт чужой layout как свой, напечатает ерунду вместо явной ошибки - это хуже
 * отказа. Молчаливое принятие несовместимой версии не является допустимым поведением.
 *
 * Библиотека намеренно НЕ реализует отклонение: она не владеет решением о печати.
 * Проверка версии и отказ - ответственность получателя печати, который знает,
 * умеет ли он печатать по этой версии.
 *
 * ## Wire-format: конверт (версия + длина payload)
 *
 * [writeToParcel] записывает `protocolVersion`, затем длину payload в байтах,
 * затем сам payload; [CREATOR] читает версию и длину ДО разбора полей.
 * Некорректная длина (отрицательная, выходящая за границы Parcel или не
 * кратная 4) и перечитывание payload сверх конверта отклоняются
 * [BadParcelableException]. Кратность 4 обязательна: позиция данных [Parcel]
 * выравнивается по 4 байта всеми примитивами, поэтому границы payload обязаны
 * совпадать с границами слов. Каждая будущая версия обязана сохранять этот
 * конверт.
 *
 * Payload зависит от версии:
 * - **текущая версия** - поля документа в layout версии
 *   [PROTOCOL_VERSION_CURRENT]: header, positions, customFields;
 * - **неизвестная версия** - слова чужого layout, сохранённые как есть в
 *   [opaquePayload] и переписанные при пересылке без изменений.
 *
 * Для неизвестной версии поля документа остаются пустыми: библиотека не
 * разбирает чужой layout. Но payload не теряется - иначе промежуточное
 * приложение, пересобирающее документ, подменило бы его пустым, и получатель
 * с подходящей версией напечатал бы пустой чек вместо исходного.
 * Round-trip неизвестной версии проверяется тестом.
 *
 * ## Правило проксирования (инвариант контракта)
 *
 * Правило действует для документов текущей версии протокола. Промежуточные
 * приложения (relay, serviceprint - любой слой между вызывающим приложением и
 * получателем печати) обязаны передавать [header] и [customFields] получателю
 * **как есть**: без фильтрации, переименования, интерпретации и
 * переупорядочивания. Ключи не имеют семантики для промежуточного слоя - значение
 * приходит от вызывающего приложения, отображение определяет получатель.
 * Переупорядочивание, склейка и скрытие полей - нарушение контракта, а не
 * оптимизация: получатель печатает то, что видит, и не может отличить
 * проксированный документ от подменённого.
 *
 * Документ чужой версии протокола хранится как [opaquePayload]: разобрать его
 * поля relay не может, поэтому содержимое такого документа промежуточный слой
 * не разбирает, не интерпретирует и не преобразует, а пересылает получателю
 * без изменений - вместе с конвертом версии и длины.
 *
 * @param protocolVersion Версия wire-layout контракта. Нормативное правило получателя
 *  при несовпадении с [PROTOCOL_VERSION_CURRENT] - отклонить документ и не печатать.
 */
data class StructuredDocument @JvmOverloads constructor(
    /**
     * Версия протокола структурированного документа (wire-layout, не версия библиотеки).
     *
     * Нормативное правило получателя: `protocolVersion != PROTOCOL_VERSION_CURRENT`
     * означает отказ от документа без печати и явную ошибку вызывающему приложению.
     * Разбор полей при несовпадающей версии запрещён.
     */
    val protocolVersion: Int,
    /**
     * Шапка документа: произвольные пары ключ-значение.
     *
     * Инвариант проксирования (для документа текущей версии протокола):
     * промежуточное приложение обязано передать эту карту
     * получателю без фильтрации, переименования, интерпретации и переупорядочивания.
     * Ключи не имеют семантики для промежуточного слоя - значение приходит от
     * вызывающего приложения, отображение определяет получатель.
     *
     * null-ключи и null-значения лежат вне типизированного контракта
     * (`Map<String, String>`); при чтении из Parcel они нормализуются в "",
     * чтобы разбор чужого парсела не прерывался. Это защита read-пути,
     * а не разрешение промежуточному слою подменять значения.
     */
    val header: Map<String, String>,
    /**
     * Позиции документа. Пустой список допустим.
     */
    val positions: List<StructuredPosition>,
    /**
     * Произвольные поля документа. Пустой список допустим.
     *
     * Инвариант проксирования (для документа текущей версии протокола):
     * промежуточное приложение обязано передать этот список
     * получателю без фильтрации, переименования, интерпретации и переупорядочивания.
     * Ключ ([PrintCustomField.key]) не имеет семантики для промежуточного слоя -
     * значение приходит от вызывающего приложения, отображение определяет получатель.
     * Порядок полей является частью передаваемого значения и сохраняется.
     */
    val customFields: List<PrintCustomField>,
    /**
     * Непрозрачный payload документа чужой версии протокола.
     *
     * Заполняется только при чтении из Parcel версии, отличной от
     * [PROTOCOL_VERSION_CURRENT]: поля такого документа пусты, а payload
     * сохраняется и переписывается при пересылке без изменений.
     *
     * null у документов текущей версии и у документов, созданных вызывающим
     * приложением вручную: библиотека не умеет собирать чужой layout.
     */
    val opaquePayload: OpaquePayload? = null
) : Parcelable {

    init {
        require(opaquePayload == null || protocolVersion != PROTOCOL_VERSION_CURRENT) {
            "Opaque payload is only allowed for a foreign protocol version"
        }
    }

    private constructor(source: Parcel, version: Int) : this(
        version,
        readStringMap(source),
        source.createTypedArrayList(StructuredPosition.CREATOR) ?: emptyList(),
        source.createTypedArrayList(PrintCustomField.CREATOR) ?: emptyList()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        val opaque = opaquePayload
        if (opaque != null) {
            // Чужой layout переписывается без изменения: получатель с этой версией
            // обязан получить ту же последовательность байт, что отправил исходный
            // писатель, иначе он разберёт подмену вместо исходного документа.
            dest.writeInt(protocolVersion)
            dest.writeInt(opaque.size)
            opaque.writeTo(dest)
            return
        }

        dest.writeInt(protocolVersion)
        val lengthPosition = dest.dataPosition()
        dest.writeInt(0)
        val payloadStart = dest.dataPosition()
        dest.writeInt(header.size)
        for ((key, value) in header) {
            dest.writeString(key)
            dest.writeString(value)
        }
        dest.writeTypedList(positions)
        dest.writeTypedList(customFields)
        val payloadEnd = dest.dataPosition()
        dest.setDataPosition(lengthPosition)
        dest.writeInt(payloadEnd - payloadStart)
        dest.setDataPosition(payloadEnd)
    }

    companion object {

        /**
         * Поддерживаемая библиотекой версия wire-layout [StructuredDocument].
         *
         * Публичная константа: получатель сравнивает её со значением
         * [StructuredDocument.protocolVersion]. Несовпадение - отказ от документа
         * без печати (правило описано в KDoc класса).
         */
        const val PROTOCOL_VERSION_CURRENT = 1

        private fun readStringMap(source: Parcel): Map<String, String> {
            val size = source.readInt()
            val result = LinkedHashMap<String, String>(if (size > 0) size else 1)
            for (index in 0 until size) {
                val key = source.readString()
                val value = source.readString()
                result[key ?: ""] = value ?: ""
            }
            return result
        }

        @JvmField
        val CREATOR: Parcelable.Creator<StructuredDocument> =
            object : Parcelable.Creator<StructuredDocument> {
                override fun createFromParcel(source: Parcel): StructuredDocument {
                    val version = source.readInt()
                    val length = source.readInt()
                    val payloadStart = source.dataPosition()
                    val payloadEnd = payloadStart.toLong() + length.toLong()

                    if (length < 0 || payloadEnd > source.dataSize().toLong()) {
                        throw BadParcelableException("Invalid StructuredDocument payload length")
                    }

                    if (length % OpaquePayload.WORD_SIZE != 0) {
                        throw BadParcelableException(
                            "StructuredDocument payload length must be a multiple of ${OpaquePayload.WORD_SIZE}"
                        )
                    }

                    if (version != PROTOCOL_VERSION_CURRENT) {
                        // Чужой layout не разбираем, но payload запоминаем целиком:
                        // чтение занимает ровно length байт, поэтому следующее
                        // значение в Parcel не потребляется.
                        val payload = OpaquePayload.read(source, length)
                        return StructuredDocument(version, emptyMap(), emptyList(), emptyList(), payload)
                    }

                    val document = StructuredDocument(source, version)
                    if (source.dataPosition().toLong() > payloadEnd) {
                        throw BadParcelableException("StructuredDocument payload overread")
                    }
                    source.setDataPosition(payloadEnd.toInt())
                    return document
                }

                override fun newArray(size: Int): Array<StructuredDocument?> =
                    arrayOfNulls(size)
            }
    }
}
