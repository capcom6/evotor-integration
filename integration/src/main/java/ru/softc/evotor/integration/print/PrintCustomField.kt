package ru.softc.evotor.integration.print

import android.os.Parcel
import android.os.Parcelable

/**
 * Произвольное поле печатного документа.
 *
 * Реализация Parcelable ручная (N1): плагин kotlin-parcelize в библиотеке не подключен.
 * Порядок полей в [writeToParcel] и чтении в [CREATOR] обязан оставаться синхронным.
 */
data class PrintCustomField(
    /**
     * Ключ поля, по которому получатель его идентифицирует
     */
    val key: String,
    /**
     * Заголовок поля, выводимый получателем
     */
    val title: String,
    /**
     * Строковое значение поля
     */
    val value: String
) : Parcelable {

    private constructor(source: Parcel) : this(
        source.readString() ?: "",
        source.readString() ?: "",
        source.readString() ?: ""
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(key)
        dest.writeString(title)
        dest.writeString(value)
    }

    companion object {

        @JvmField
        val CREATOR: Parcelable.Creator<PrintCustomField> =
            object : Parcelable.Creator<PrintCustomField> {
                override fun createFromParcel(source: Parcel): PrintCustomField =
                    PrintCustomField(source)

                override fun newArray(size: Int): Array<PrintCustomField?> =
                    arrayOfNulls(size)
            }
    }
}