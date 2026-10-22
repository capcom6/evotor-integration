package ru.softc.evotor.integration.print

import android.os.Parcel
import android.os.Parcelable
import java.math.BigDecimal

/**
 * Позиция структурированного печатного документа.
 *
 * Реализация Parcelable ручная (N1): плагин kotlin-parcelize в библиотеке не подключен.
 * Порядок полей в [writeToParcel] и чтении в [CREATOR] обязан оставаться синхронным.
 *
 * @param quantity Количество. Передаётся как [BigDecimal], потому что примитив теряет
 *   точность и scale: 2.500 и 2.5 — разные значения для получателя (N2, D4).
 * @param measurePrecision Количество знаков после запятой из framework Position.measurePrecision.
 *   Нужен получателю для форматирования количества (N3).
 */
data class StructuredPosition(
    /**
     * Наименование позиции
     */
    val name: String,
    /**
     * Количество. Строковое представление определяет конечный получатель (D4).
     */
    val quantity: BigDecimal,
    /**
     * Количество знаков после запятой при отображении количества (N3)
     */
    val measurePrecision: Int,
    /**
     * Идентификатор товара в учётной системе. null, если товар не привязан.
     */
    val productUuid: String?,
    /**
     * Подпозиции. Пустой список для позиции без подпозиций.
     */
    val subPositions: List<StructuredPosition>
) : Parcelable {

    private constructor(source: Parcel) : this(
        source.readString() ?: "",
        source.readSerializable() as? BigDecimal ?: BigDecimal.ZERO,
        source.readInt(),
        source.readString(),
        source.createTypedArrayList(CREATOR) ?: emptyList()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
        // N2: writeSerializable сохраняет unscaledValue и scale, String-конверт терял бы scale.
        dest.writeSerializable(quantity)
        dest.writeInt(measurePrecision)
        dest.writeString(productUuid)
        dest.writeTypedList(subPositions)
    }

    companion object {

        @JvmField
        val CREATOR: Parcelable.Creator<StructuredPosition> =
            object : Parcelable.Creator<StructuredPosition> {
                override fun createFromParcel(source: Parcel): StructuredPosition =
                    StructuredPosition(source)

                override fun newArray(size: Int): Array<StructuredPosition?> =
                    arrayOfNulls(size)
            }
    }
}