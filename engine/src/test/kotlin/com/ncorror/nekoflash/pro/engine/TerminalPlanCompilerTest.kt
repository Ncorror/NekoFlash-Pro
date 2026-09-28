package com.ncorror.nekoflash.pro.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TerminalPlanCompilerTest {
    private val compiler = TerminalPlanCompiler()

    private fun steps(line: String): List<TerminalPlanStep> =
        assertIs<TerminalPlanResult.Ready>(compiler.compile(line), line).plan.steps

    @Test
    fun anyVariableIsReadWithoutAnAllowList() {
        assertEquals(listOf(TerminalPlanStep.FastbootGetvar("serialno")), steps("fastboot getvar serialno"))
        assertEquals(listOf(TerminalPlanStep.FastbootGetvar("partition-size:userdata")),
            steps("fastboot getvar partition-size:userdata"))
        assertEquals(listOf(TerminalPlanStep.FastbootGetvarAll), steps("fastboot getvar all"))
        assertEquals(listOf(TerminalPlanStep.FastbootGetvarAll), steps("fastboot getvar"))
    }

    @Test
    fun wireCommandsGoAsTheDeviceKnowsThem() {
        assertEquals(listOf(TerminalPlanStep.FastbootWire("oem unlock")), steps("fastboot oem unlock"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("reboot-bootloader")), steps("fastboot reboot bootloader"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("getvar:product")), steps("fastboot raw getvar:product"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("erase:cache", CURRENT_SLOT)), steps("fastboot erase cache"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("set_active:b", CURRENT_SLOT)), steps("fastboot set_active b"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("flashing unlock")), steps("fastboot flashing unlock"))
        // Неизвестный глагол — vendor-команда устройства (`04` §3, правило 3).
        assertEquals(listOf(TerminalPlanStep.FastbootWire("vendor-thing x")), steps("fastboot vendor-thing x"))
    }

    @Test
    fun flashBindsOnePickedFileAndSetActiveFollowsIt() {
        val flash = steps("fastboot flash boot_a boot.img")
        assertEquals(listOf(TerminalPlanStep.FastbootFlashImage("boot_a", "boot.img", CURRENT_SLOT)), flash)
        assertEquals(TerminalFileNeed.OPEN, flash.single().fileNeed)
        assertEquals(true, flash.single().writesPartition)

        assertEquals(
            listOf(
                TerminalPlanStep.FastbootFlashImage("boot", "boot.img", CURRENT_SLOT),
                TerminalPlanStep.FastbootWire("set_active:a", CURRENT_SLOT),
            ),
            steps("fastboot --set-active=a flash boot boot.img"),
        )
    }

    @Test
    fun hostScenariosAndUnusableOptionsAreNotImplementedBeforeSending() {
        for (command in listOf(
            "fastboot --force getvar product",
            "fastboot -w getvar version",
            "fastboot --slot all fetch boot boot.img",
            "fastboot --slot a getvar product",
            "fastboot --set-active=a getvar product",
            "fastboot -S 512M erase system",
            "fastboot format userdata",
            "fastboot -w flashall",
            "fastboot flash vendor_boot:default",
            "fastboot boot kernel ramdisk",
            "adb install --no-streaming app.apk",
            "adb install --fastdeploy app.apk",
            "fastboot -s ABC devices",
        )) {
            assertIs<TerminalPlanResult.NotImplemented>(compiler.compile(command), command)
        }
        assertIs<TerminalPlanResult.Invalid>(compiler.compile("fastboot -S --force flash boot boot.img"))
    }

    @Test
    fun flashallAndUpdateTakeTheirOptions() {
        assertEquals(listOf(TerminalPlanStep.FastbootFlashAll(archiveName = null)), steps("fastboot flashall"))
        assertEquals(
            listOf(
                TerminalPlanStep.FastbootFlashAll(
                    archiveName = "image.zip",
                    slot = "b",
                    force = true,
                    skipSecondary = true,
                    skipReboot = true,
                    vbmetaFlags = 3,
                    sparseLimit = 1024L * 1024L,
                ),
            ),
            steps("fastboot --slot b --force --skip-secondary --skip-reboot --disable-verity --disable-verification -S 1M update image.zip"),
        )
        assertEquals(TerminalFileNeed.FOLDER, steps("fastboot flashall").single().fileNeed)
        assertEquals(TerminalFileNeed.OPEN, steps("fastboot update image.zip").single().fileNeed)
        assertEquals(
            listOf(TerminalPlanStep.FastbootWipeSuper("super_empty.img", slot = "a", sparseLimit = 4096L)),
            steps("fastboot --slot a -S 4K wipe-super"),
        )
        assertEquals(
            listOf(TerminalPlanStep.FastbootFlashVendorRamdisk("dlkm", "ramdisk.img", slot = "b")),
            steps("fastboot --slot b flash vendor_boot:dlkm ramdisk.img"),
        )
    }

    @Test
    fun adbCommandsUseTheServicesOfTheDesktopClient() {
        assertEquals(listOf(TerminalPlanStep.AdbShellCommand("id")), steps("adb shell id"))
        assertEquals(listOf(TerminalPlanStep.AdbShellCommand("id")), steps("adb shell -T id"))
        assertEquals(listOf(TerminalPlanStep.AdbService("shell:id")), steps("adb shell -x id"))
        assertEquals(listOf(TerminalPlanStep.AdbInteractiveShell()), steps("adb shell"))
        assertEquals(listOf(TerminalPlanStep.AdbInteractiveShell("top")), steps("adb shell -t top"))
        assertEquals(listOf(TerminalPlanStep.AdbInteractiveShell("logcat -s 'My Tag' -v time")),
            steps("adb logcat -s 'My Tag' -v time"))
        assertEquals(listOf(TerminalPlanStep.AdbService("exec:ls /")), steps("adb exec ls /"))
        assertEquals(listOf(TerminalPlanStep.AdbService("root:")), steps("adb root"))
        assertEquals(listOf(TerminalPlanStep.AdbService("remount:-R")), steps("adb remount -R"))
        assertEquals(listOf(TerminalPlanStep.AdbService("tcpip:5555")), steps("adb tcpip 5555"))
        assertEquals(listOf(TerminalPlanStep.AdbReboot("recovery")), steps("adb reboot recovery"))
        assertEquals(listOf(TerminalPlanStep.AdbReboot("")), steps("adb reboot"))
        assertEquals(listOf(TerminalPlanStep.AdbService("exec:cmd package uninstall -k com.example")),
            steps("adb uninstall -k com.example"))
        assertEquals(listOf(TerminalPlanStep.AdbSideload("update.zip")), steps("adb sideload update.zip"))
    }

    @Test
    fun transfersFollowTheDesktopClientRules() {
        assertEquals(listOf(TerminalPlanStep.AdbPull(listOf("/sdcard/a.txt"), null)), steps("adb pull /sdcard/a.txt"))
        assertEquals(listOf(TerminalPlanStep.AdbPull(listOf("/sdcard/DCIM"), "photos")), steps("adb pull /sdcard/DCIM photos"))
        assertEquals(listOf(TerminalPlanStep.AdbPull(listOf("/a", "/b"), null)), steps("adb pull -q /a /b out/"))
        assertEquals(listOf(TerminalPlanStep.AdbPush(listOf("a.txt"), "/sdcard/")), steps("adb push a.txt /sdcard/"))
        assertEquals(listOf(TerminalPlanStep.AdbPush(listOf("a", "b"), "/sdcard", sync = true)),
            steps("adb push --sync -Z a b /sdcard"))
        assertEquals(TerminalFileNeed.OPEN_OR_FOLDER, steps("adb push photos /sdcard/").single().fileNeed)
        assertEquals(TerminalFileNeed.OPEN_MANY, steps("adb push a b /sdcard/").single().fileNeed)
        assertEquals(TerminalFileNeed.FOLDER, steps("adb pull /sdcard/a").single().fileNeed)
        assertEquals(listOf(TerminalPlanStep.AdbSync("all", false)), steps("adb sync"))
        assertEquals(listOf(TerminalPlanStep.AdbSync("vendor", true)), steps("adb sync -l vendor"))
        assertEquals(TerminalFileNeed.FOLDER, steps("adb sync").single().fileNeed)
        assertEquals(listOf(TerminalPlanStep.AdbPush(listOf("a"), "/sdcard/", dryRun = true)), steps("adb push -n a /sdcard/"))
        assertEquals(listOf(TerminalPlanStep.AdbSync("all", false, dryRun = true)), steps("adb sync -n"))
        for (command in listOf(
            "adb pull -a /sdcard/a.txt", "adb pull -z brotli /sdcard/a", "adb push -z lz4 a /sdcard/",
        )) {
            assertIs<TerminalPlanResult.NotImplemented>(compiler.compile(command), command)
        }
    }

    @Test
    fun installPassesPackageFlagsAndSplitsCombinedOnes() {
        assertEquals(listOf(TerminalPlanStep.AdbInstall(listOf("app.apk"), listOf("-r", "-g"), false)),
            steps("adb install -rg app.apk"))
        assertEquals(listOf(TerminalPlanStep.AdbInstall(listOf("a.apk", "b.apk"), listOf("--abi", "arm64-v8a"), true)),
            steps("adb install-multiple --streaming --abi arm64-v8a a.apk b.apk"))
        assertEquals(TerminalFileNeed.OPEN_MANY, steps("adb install-multiple a.apk").single().fileNeed)
    }

    /** `fastboot.cpp`: без значения `--set-active` — слот из `--slot`, без него текущий. */
    @Test
    fun setActiveWithoutASlotMeansTheSlotOptionOrTheCurrentOne() {
        assertEquals(
            listOf(
                TerminalPlanStep.FastbootFlashImage("boot", "boot.img", CURRENT_SLOT),
                TerminalPlanStep.FastbootWire(SET_ACTIVE_DEFAULT, CURRENT_SLOT),
            ),
            steps("fastboot --set-active flash boot boot.img"),
        )
        assertEquals(listOf(TerminalPlanStep.FastbootWire(SET_ACTIVE_DEFAULT, CURRENT_SLOT)), steps("fastboot --set-active"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire(SET_ACTIVE_DEFAULT, "b")), steps("fastboot --slot b --set-active"))
        assertEquals(false, compiler.compile("fastboot set_active ''") is TerminalPlanResult.Ready)
    }

    @Test
    fun rawAndOemGoWithoutASlot() {
        assertEquals(listOf(TerminalPlanStep.FastbootWire("erase:cache")), steps("fastboot raw erase:cache"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("oem device-info")), steps("fastboot oem device-info"))
    }

    @Test
    fun verityFlagsPatchTheVbmetaImageOnly() {
        assertEquals(
            listOf(TerminalPlanStep.FastbootFlashImage("vbmeta", "vbmeta.img", CURRENT_SLOT, vbmetaFlags = 3)),
            steps("fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img"),
        )
        assertIs<TerminalPlanResult.NotImplemented>(compiler.compile("fastboot --disable-verity flash boot boot.img"))
    }

    @Test
    fun sparseLimitRidesOnFlash() {
        assertEquals(
            listOf(TerminalPlanStep.FastbootFlashImage("system", "system.img", CURRENT_SLOT, sparseLimit = 512L shl 20)),
            steps("fastboot -S 512M flash system system.img"),
        )
        // `-S 0` — как без `-S`: предел назовёт устройство.
        assertEquals(listOf(TerminalPlanStep.FastbootFlashImage("system", "system.img", CURRENT_SLOT)),
            steps("fastboot -S 0 flash system system.img"))
    }

    @Test
    fun slotRidesOnFlashAndErase() {
        assertEquals(listOf(TerminalPlanStep.FastbootFlashImage("boot", "boot.img", "a")),
            steps("fastboot --slot a flash boot boot.img"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("erase:cache", "all")), steps("fastboot --slot=all erase cache"))
        assertEquals(listOf(TerminalPlanStep.FastbootFlashImage("boot", "boot.img", "all")),
            steps("fastboot --slot all flash boot boot.img"))
        assertEquals(listOf(TerminalPlanStep.FastbootFetch("boot", "boot.img", "b")), steps("fastboot --slot b fetch boot boot.img"))
    }

    @Test
    fun shellWithoutACommandOpensTheModeItNames() {
        assertEquals(listOf(TerminalPlanStep.AdbInteractiveShell(mode = ShellMode.LEGACY)), steps("adb shell -x"))
        assertEquals(listOf(TerminalPlanStep.AdbInteractiveShell(mode = ShellMode.RAW)), steps("adb shell -T"))
        assertEquals(listOf(TerminalPlanStep.AdbInteractiveShell(mode = ShellMode.PTY)), steps("adb shell -t"))
    }

    @Test
    fun devicesStateAndStatusAreAnsweredByTheApp() {
        fun report(tool: TerminalParser.Tool, kind: LocalReportKind) = listOf(TerminalPlanStep.LocalReport(tool, kind))
        assertEquals(report(TerminalParser.Tool.ADB, LocalReportKind.DEVICES), steps("adb devices"))
        assertEquals(report(TerminalParser.Tool.ADB, LocalReportKind.DEVICES_LONG), steps("adb devices -l"))
        assertEquals(report(TerminalParser.Tool.ADB, LocalReportKind.GET_STATE), steps("adb get-state"))
        assertEquals(report(TerminalParser.Tool.ADB, LocalReportKind.STATUS), steps("adb status"))
        assertEquals(report(TerminalParser.Tool.FASTBOOT, LocalReportKind.DEVICES), steps("fastboot devices"))
        assertEquals(report(TerminalParser.Tool.FASTBOOT, LocalReportKind.DEVICES_LONG), steps("fastboot devices -l"))
        assertEquals(report(TerminalParser.Tool.FASTBOOT, LocalReportKind.STATUS), steps("fastboot status"))
        assertIs<TerminalPlanResult.NotImplemented>(compiler.compile("fastboot --set-active=a devices"))
    }

    @Test
    fun anUnknownAdbVerbIsNotTurnedIntoShell() {
        assertIs<TerminalPlanResult.NotImplemented>(compiler.compile("adb getprop ro.product.device"))
    }
}
