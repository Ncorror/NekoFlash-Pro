package io.github.ncorror.nekoflash.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Сведения о хосте и сборке для диагностического отчёта.
 *
 * `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md` требует от evidence идентичность
 * сборки и хоста: без них отчёт нельзя сопоставить ни с версией приложения, ни
 * с телефоном, на котором наблюдалось поведение.
 *
 * Ничего не вырезается. Отчёт собирает оператор о собственном устройстве и сам
 * решает, что с ним делать. Урезать состав «на всякий случай» означало бы
 * host-side запрет, который запрещён `01_PRODUCT_CHARTER_RU.md`.
 *
 * Поле, которого платформа не даёт, записывается с причиной, а не пропускается:
 * пустое место читается как «этого не было».
 */
public object HostFacts {
    /**
     * Собирает всё, что доступно без запроса дополнительных разрешений.
     *
     * Там, где платформа сведений не отдаёт, записывается причина отказа.
     * Подменять ответ платформы собственным умолчанием запрещено
     * `03_PROTOCOL_AND_SAFETY_INVARIANTS_RU.md`, а пустое место в отчёте
     * читалось бы как «этого у устройства нет».
     */
    public fun collect(context: Context): Map<String, String> = buildMap {
        put("app.applicationId", context.packageName)
        putAll(appVersion(context))

        put("host.manufacturer", Build.MANUFACTURER)
        put("host.brand", Build.BRAND)
        put("host.model", Build.MODEL)
        put("host.device", Build.DEVICE)
        put("host.product", Build.PRODUCT)
        put("host.hardware", Build.HARDWARE)
        put("host.fingerprint", Build.FINGERPRINT)
        put("host.buildId", Build.ID)
        put("host.androidRelease", Build.VERSION.RELEASE)
        put("host.sdkInt", Build.VERSION.SDK_INT.toString())
        put("host.securityPatch", Build.VERSION.SECURITY_PATCH)
        put("host.supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))

        put("host.usbHostFeature", usbHostFeature(context))
        put("app.permissions", permissions(context))
        put("host.androidId", androidId(context))
        put("host.serial", HOST_SERIAL_UNAVAILABLE)
    }

    /**
     * Состояние разрешения, как его видит сама платформа.
     *
     * Строка вида `INTERNET=granted`. Нужна ровно затем, зачем нужен
     * [usbHostFeature]: отличить «приложение не просило» от «просило, дали, а
     * не работает всё равно». Прогон `07` §6.52 упёрся именно в это — сокет
     * отказывал на создании, и по журналу нельзя было понять, чей это отказ.
     */
    public fun permissionState(context: Context, permission: String): String =
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            GRANTED
        } else {
            DENIED
        }

    /**
     * Все объявленные разрешения с их состоянием.
     *
     * Объявленное, но не выданное разрешение — самая частая причина «работает у
     * меня и не работает у тебя», и в отчёте оно должно быть видно, а не
     * выводиться из поведения.
     */
    private fun permissions(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions
            ?.joinToString(",") { name -> "${name.substringAfterLast('.')}=${permissionState(context, name)}" }
            ?: NONE_DECLARED
    } catch (unavailable: PackageManager.NameNotFoundException) {
        "$UNAVAILABLE:${unavailable.javaClass.simpleName}"
    }

    /**
     * Сообщает ли платформа о поддержке host-режима USB.
     *
     * Без него `getDeviceList` пуст на любом аппарате, и приложению нечего
     * показывать. Факт попадает в evidence, чтобы «ничего не видно» можно было
     * отличить от «система говорит, что host-режима нет».
     */
    private fun usbHostFeature(context: Context): String =
        context.packageManager.hasSystemFeature("android.hardware.usb.host").toString()

    private fun appVersion(context: Context): Map<String, String> = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        mapOf(
            "app.versionName" to (info.versionName ?: UNAVAILABLE),
            "app.versionCode" to versionCode(info).toString(),
        )
    } catch (unavailable: PackageManager.NameNotFoundException) {
        mapOf(
            "app.versionName" to "$UNAVAILABLE:${unavailable.javaClass.simpleName}",
            "app.versionCode" to UNAVAILABLE,
        )
    }

    /**
     * Идентификатор установки, выдаваемый платформой.
     *
     * Помогает сопоставить несколько отчётов с одного хоста между собой.
     */
    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: UNAVAILABLE

    /**
     * Номер версии пакета.
     *
     * `PackageInfo.longVersionCode` появился в API 28, а минимальная
     * поддерживаемая версия — 26, поэтому на более старых устройствах берётся
     * устаревшее поле. Без проверки уровня приложение падало бы на Android 8.
     */
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            legacyVersionCode(info)
        }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()

    private const val UNAVAILABLE = "unavailable"
    private const val NONE_DECLARED = "none"

    /** Ответ платформы называется её словами, а не пересказывается. */
    private const val GRANTED = "granted"
    private const val DENIED = "denied"

    /**
     * Серийный номер хоста платформа не отдаёт обычному приложению.
     *
     * `Build.getSerial()` требует `READ_PRIVILEGED_PHONE_STATE` — разрешения
     * системного уровня, которое обычному приложению не выдаётся ни при каких
     * действиях пользователя. Вызов не делается вовсе: он не может завершиться
     * успешно, а обёрнутый в перехват исключения выглядел бы как попытка, хотя
     * попыткой не является.
     *
     * Причина записывается в отчёт: пустое место читалось бы как «серийного
     * номера у устройства нет», что неверно. Это отказ платформы, а не решение
     * приложения.
     */
    private const val HOST_SERIAL_UNAVAILABLE = "unavailable:requires_privileged_permission"
}
