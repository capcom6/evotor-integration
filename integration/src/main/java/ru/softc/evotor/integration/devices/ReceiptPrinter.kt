package ru.softc.evotor.integration.devices

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BaseColumns
import ru.evotor.devices.commons.printer.PrinterDocument
import ru.softc.evotor.integration.print.StructuredDocument
import java.util.*

/**
 * Created by capcom on 11.12.2017.
 */
class ReceiptPrinter private constructor(
    /**
     * Идентификатор принтера
     */
    val id: Long,
    /**
     * Наименование принтера
     */
    val name: String,
    /**
     * Ширина печати в символах стандартного шрифта
     */
    val allowableSymbolsLineLength: Int
) {
    override fun toString(): String {
        return "Id: " + id + "; Name: " + name + "; Width: " + allowableSymbolsLineLength
    }

    companion object {
        const val PERMISSION = "ru.softc.permission.receiptprinter.PRINT"

        private const val ACTION_PRINT_DOCUMENT = "ru.softc.evotorserviceprinter.ACTION_PRINT_DOCUMENT"
        private const val PACKAGE_NAME = "ru.softc.evotorserviceprinter"
        private const val ACTIVITY_NAME = "ru.softc.evotorserviceprinter.MainActivity"
        @Deprecated("Переходим на работу через BroadcastReceiver или binded Service")
        private const val SERVICE_NAME = "ru.softc.evotorserviceprinter.PrinterDriverService"
        private const val RECEIVER_NAME = "ru.softc.evotorserviceprinter.EventsReceiver"

        /**
         * Минимальная версия драйвера, поддерживающая структурированную печать (D3)
         */
        private const val STRUCTURED_PRINT_MIN_DRIVER_VERSION = 142

        private const val ACTION_PRINT_STRUCTURED =
            "ru.softc.evotorserviceprinter.ACTION_PRINT_STRUCTURED"

        ////////////////////////////////////////////////////////////////////////////////////////////
        @JvmStatic
        fun createIntentForSettings(): Intent {
            val name = ComponentName(
                PACKAGE_NAME,
                ACTIVITY_NAME
            )
            val starter = Intent()
            starter.component = name
            starter.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return starter
        }

        /**
         * @param context Контекст приложения
         * @return Наличие драйвера на устройстве
         */
        @JvmStatic
        fun isDriverInstalled(context: Context): Boolean {
            val packageManager = context.packageManager
            try {
                packageManager.getPackageInfo(PACKAGE_NAME, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return false
            }
            return true
        }

        @JvmStatic
        fun driverVersion(context: Context): Int? {
            try {
                return context.packageManager.getPackageInfo(PACKAGE_NAME, 0).versionCode
            } catch (e: Exception) {
                return null
            }
        }

        /**
         * @param context Контекст приложения
         * @return Список настроенный принтеров
         */
        @JvmStatic
        fun getPrinters(context: Context): Array<ReceiptPrinter> {
            val printers =
                ArrayList<ReceiptPrinter>()
            val cursor = context.contentResolver.query(
                Uri.parse("content://ru.softc.receiptprinter.Printers"),
                null,
                null,
                null,
                null
            )
            try {
                val idIndex = cursor!!.getColumnIndex(BaseColumns._ID)
                val nameIndex = cursor.getColumnIndex("name")
                val widthIndex = cursor.getColumnIndex("symbol_width")
                while (cursor.moveToNext()) {
                    printers.add(
                        ReceiptPrinter(
                            cursor.getLong(idIndex),
                            cursor.getString(nameIndex),
                            cursor.getInt(widthIndex)
                        )
                    )
                }
            } finally {
                cursor!!.close()
            }
            return printers.toTypedArray()
        }

        /**
         * @param context Контекст приложения
         * @param id Идентификатор принтера
         * @return Найденный принтер или null в случае его отсутствия
         */
        @JvmStatic
        fun getPrinter(context: Context, id: Long): ReceiptPrinter? {
            val cursor = context.contentResolver.query(
                Uri.parse("content://ru.softc.receiptprinter.Printers/$id"),
                null,
                null,
                null,
                null
            )
            try {
                val idIndex = cursor!!.getColumnIndex(BaseColumns._ID)
                val nameIndex = cursor.getColumnIndex("name")
                val widthIndex = cursor.getColumnIndex("symbol_width")
                if (cursor.moveToNext()) {
                    return ReceiptPrinter(
                        cursor.getLong(idIndex),
                        cursor.getString(nameIndex),
                        cursor.getInt(widthIndex)
                    )
                }
            } finally {
                cursor!!.close()
            }
            return null
        }

        /**
         * @param context Контекст приложения
         * @param printerId Идентификатор принтера
         * @param document Документ для печати
         */
        @JvmStatic
        fun printDocument(
            context: Context,
            printerId: Long,
            document: PrinterDocument
        ) {
            val version = driverVersion(context) ?: throw RuntimeException("Драйвер не установлен")

            if (version >= 116) {
                context.sendBroadcast(Intent(ACTION_PRINT_DOCUMENT).apply {
                    component = ComponentName(PACKAGE_NAME, RECEIVER_NAME)
                    putExtra("EXTRA_PRINTER_ID", printerId)
                    putExtra("EXTRA_DOCUMENT", document)
                })

                return
            }

            val name = ComponentName(
                PACKAGE_NAME,
                SERVICE_NAME
            )
            val starter = Intent(ACTION_PRINT_DOCUMENT)
            starter.putExtra("EXTRA_PRINTER_ID", printerId)
            starter.putExtra("EXTRA_DOCUMENT", document)
            starter.component = name
            context.startService(starter)
        }

        /**
         * Печать структурированного документа на конкретный принтер через драйвер.
         *
         * @param context Контекст приложения
         * @param printerId Идентификатор принтера
         * @param document Структурированный документ
         * @return true, если документ отправлен драйверу; false, если драйвер не установлен
         *   либо его версия ниже порога [STRUCTURED_PRINT_MIN_DRIVER_VERSION] (D3).
         *   При false ничего не отправляется — вызывающий код обязан деградировать сам (D1).
         */
        @JvmStatic
        fun printStructuredDocument(
            context: Context,
            printerId: Long,
            document: StructuredDocument
        ): Boolean {
            val version = driverVersion(context) ?: return false
            if (version < STRUCTURED_PRINT_MIN_DRIVER_VERSION) {
                return false
            }

            context.sendBroadcast(Intent(ACTION_PRINT_STRUCTURED).apply {
                component = ComponentName(PACKAGE_NAME, RECEIVER_NAME)
                putExtra("EXTRA_PRINTER_ID", printerId)
                putExtra("EXTRA_DOCUMENT", document)
            })
            return true
        }
    }

}