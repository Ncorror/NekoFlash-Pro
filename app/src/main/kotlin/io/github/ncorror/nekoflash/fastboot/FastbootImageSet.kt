package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.artifact.LocalEntry
import io.github.ncorror.nekoflash.artifact.LocalFolder
import io.github.ncorror.nekoflash.artifact.ZipArchive
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource

/**
 * Набор образов `flashall`/`update`: папка вместо `$ANDROID_PRODUCT_OUT`
 * (`LocalImageSource`) или архив (`ZipImageSource`).
 */
internal interface FastbootImageSet {
    /** Образ как источник; `null` — его нет. */
    fun source(name: String): ArtifactSource?
}

/** Папка, выбранная через Android: файлы — прямо в ней, как `$OUT/ИМЯ`. */
internal class FolderImageSet(folder: LocalFolder) : FastbootImageSet {
    private val files by lazy { folder.entries().filterIsInstance<LocalEntry.File>().associateBy { it.name } }

    override fun source(name: String): ArtifactSource? = files[name]?.open?.invoke()
}

/** Архив `update.zip`. */
internal class ZipImageSet(private val archive: ZipArchive) : FastbootImageSet {
    override fun source(name: String): ArtifactSource? = archive.source(name)
}

/** Чем образ служит в `flashall`: загрузочный, системный или лишний (не прошивается). */
internal enum class ImageType { BOOT_CRITICAL, NORMAL, EXTRA }

/**
 * Строка таблицы `images` из AOSP `fastboot/fastboot.cpp`.
 *
 * Пустой [nickname] — образ второго слота (`boot_other.img`): он идёт в слот,
 * противоположный текущему, и пропускается при `--skip-secondary`.
 */
internal data class FlashAllImage(
    val nickname: String,
    val imageName: String,
    val partition: String,
    val optional: Boolean,
    val type: ImageType,
) {
    val secondary: Boolean get() = nickname.isEmpty()
}

/** Таблица `images` в порядке AOSP. */
internal val FLASH_ALL_IMAGES: List<FlashAllImage> = listOf(
    FlashAllImage("boot", "boot.img", "boot", false, ImageType.BOOT_CRITICAL),
    FlashAllImage("bootloader", "bootloader.img", "bootloader", true, ImageType.EXTRA),
    FlashAllImage("init_boot", "init_boot.img", "init_boot", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("", "boot_other.img", "boot", true, ImageType.NORMAL),
    FlashAllImage("cache", "cache.img", "cache", true, ImageType.EXTRA),
    FlashAllImage("dtbo", "dtbo.img", "dtbo", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("dts", "dt.img", "dts", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("odm", "odm.img", "odm", true, ImageType.NORMAL),
    FlashAllImage("odm_dlkm", "odm_dlkm.img", "odm_dlkm", true, ImageType.NORMAL),
    FlashAllImage("product", "product.img", "product", true, ImageType.NORMAL),
    FlashAllImage("pvmfw", "pvmfw.img", "pvmfw", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("radio", "radio.img", "radio", true, ImageType.EXTRA),
    FlashAllImage("recovery", "recovery.img", "recovery", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("super", "super.img", "super", true, ImageType.EXTRA),
    FlashAllImage("system", "system.img", "system", false, ImageType.NORMAL),
    FlashAllImage("system_dlkm", "system_dlkm.img", "system_dlkm", true, ImageType.NORMAL),
    FlashAllImage("system_ext", "system_ext.img", "system_ext", true, ImageType.NORMAL),
    FlashAllImage("", "system_other.img", "system", true, ImageType.NORMAL),
    FlashAllImage("userdata", "userdata.img", "userdata", true, ImageType.EXTRA),
    FlashAllImage("vbmeta", "vbmeta.img", "vbmeta", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("vbmeta_system", "vbmeta_system.img", "vbmeta_system", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("vbmeta_vendor", "vbmeta_vendor.img", "vbmeta_vendor", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("vendor", "vendor.img", "vendor", true, ImageType.NORMAL),
    FlashAllImage("vendor_boot", "vendor_boot.img", "vendor_boot", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("vendor_dlkm", "vendor_dlkm.img", "vendor_dlkm", true, ImageType.NORMAL),
    FlashAllImage("vendor_kernel_boot", "vendor_kernel_boot.img", "vendor_kernel_boot", true, ImageType.BOOT_CRITICAL),
    FlashAllImage("", "vendor_other.img", "vendor", true, ImageType.NORMAL),
)
