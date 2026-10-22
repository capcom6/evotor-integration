package ru.softc.evotor.integration.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ru.evotor.devices.commons.printer.printable.IPrintable
import ru.evotor.framework.receipt.Position
import ru.softc.evotor.integration.print.StructuredDocument
import java.util.*

/**
 * Created by capcom on 19.12.2017.
 */
object ServicePrint {
    const val PERMISSION = "ru.softc.permission.serviceprint.PRINT"
    private const val PACKAGE_NAME = "ru.softc.evotor.serviceprint"
    @Deprecated("Переходим на работу через BroadcastReceiver")
    private const val SERVICE_NAME = "ru.softc.evotor.serviceprint.services.PrintDivideService"
    private const val RECIEVER_NAME = "ru.softc.evotor.serviceprint.recievers.EventsReceiver"
    private const val ACTIVITY_NAME = "ru.softc.evotor.serviceprint.MainActivity"

    /**
     * Минимальная версия serviceprint, поддерживающая структурированную печать (D3)
     */
    private const val STRUCTURED_PRINT_MIN_APP_VERSION = 19

    private const val ACTION_PRINT_STRUCTURED = "ru.softc.evotor.serviceprint.ACTION_PRINT_STRUCTURED"

    @JvmStatic
    fun createIntentForSettings(): Intent {
        val name = ComponentName(PACKAGE_NAME, ACTIVITY_NAME)
        val starter = Intent()
        starter.component = name
        starter.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return starter
    }

    @JvmStatic
    fun isAppInstalled(context: Context): Boolean {
        val packageManager = context.packageManager
        try {
            packageManager.getPackageInfo(PACKAGE_NAME, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return true
    }

    @JvmStatic
    fun appVersion(context: Context): Int? {
        try {
            return context.packageManager.getPackageInfo(PACKAGE_NAME, 0).versionCode
        } catch (e: Exception) {
            return null
        }
    }

    @JvmStatic
    fun printDocuments(
        context: Context,
        header: ArrayList<IPrintable>,
        positions: ArrayList<Position>
    ) {
        val appVersion = appVersion(context) ?: return
        if (appVersion >= 10) {
            context.sendBroadcast(Intent("ru.softc.evotor.serviceprint.ACTION_PRINT_DIVIDE").apply {
                component = ComponentName(PACKAGE_NAME, RECIEVER_NAME)
                putExtra("EXTRA_HEADER", header)
                putExtra("EXTRA_POSITIONS", positions)
            })
            return
        }

        val name = ComponentName(PACKAGE_NAME, SERVICE_NAME)
        val starter = Intent()
        starter.putExtra("EXTRA_HEADER", header)
        starter.putExtra("EXTRA_POSITIONS", positions)
        starter.component = name
        context.startService(starter)
    }

    /**
     * Печать структурированного документа через serviceprint.
     *
     * @param context Контекст приложения
     * @param document Структурированный документ
     * @return true, если документ отправлен получателю; false, если serviceprint
     *   не установлен либо его версия ниже порога [STRUCTURED_PRINT_MIN_APP_VERSION] (D3).
     *   При false ничего не отправляется — вызывающий код обязан деградировать сам (D1).
     */
    @JvmStatic
    fun printStructured(context: Context, document: StructuredDocument): Boolean {
        val version = appVersion(context) ?: return false
        if (version < STRUCTURED_PRINT_MIN_APP_VERSION) {
            return false
        }

        context.sendBroadcast(Intent(ACTION_PRINT_STRUCTURED).apply {
            component = ComponentName(PACKAGE_NAME, RECIEVER_NAME)
            putExtra("EXTRA_DOCUMENT", document)
        })
        return true
    }
}