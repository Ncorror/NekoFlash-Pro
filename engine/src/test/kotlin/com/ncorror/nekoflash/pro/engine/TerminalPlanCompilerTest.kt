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
        assertEquals(listOf(TerminalPlanStep.FastbootWire("erase:cache")), steps("fastboot erase cache"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("set_active:b")), steps("fastboot set_active b"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("flashing unlock")), steps("fastboot flashing unlock"))
        // Неизвестный глагол — vendor-команда устройства (`04` §3, правило 3).
        assertEquals(listOf(TerminalPlanStep.FastbootWire("vendor-thing x")), steps("fastboot vendor-thing x"))
    }

    @Test
    fun flashBindsOnePickedFileAndSetActiveFollowsIt() {
        val flash = steps("fastboot flash boot_a boot.img")
        assertEquals(listOf(TerminalPlanStep.FastbootFlashImage("boot_a", "boot.img")), flash)
        assertEquals(TerminalFileNeed.OPEN, flash.single().fileNeed)
        assertEquals(true, flash.single().writesPartition)

        assertEquals(
            listOf(
                TerminalPlanStep.FastbootFlashImage("boot", "boot.img"),
                TerminalPlanStep.FastbootWire("set_active:a"),
            ),
            steps("fastboot --set-active=a flash boot boot.img"),
        )
    }

    @Test
    fun hostScenariosAndUnusableOptionsAreNotImplementedBeforeSending() {
        for (command in listOf(
            "fastboot --force getvar product",
            "fastboot -w getvar version",
            "fastboot --slot all flash boot boot.img",
            "fastboot --slot a getvar product",
            "fastboot --set-active=a getvar product",
            "fastboot -S 512M flash system system.img",
            "fastboot format userdata",
            "fastboot wipe-super super_empty.img",
            "fastboot flashall",
            "fastboot update image.zip",
            "fastboot flash vendor_boot:default ramdisk.img",
            "fastboot boot kernel ramdisk",
            "adb install --no-streaming app.apk",
            "adb install --fastdeploy app.apk",
            "adb shell -x",
            "adb shell -T",
        )) {
            assertIs<TerminalPlanResult.NotImplemented>(compiler.compile(command), command)
        }
        assertIs<TerminalPlanResult.Invalid>(compiler.compile("fastboot -S --force flash boot boot.img"))
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
    fun transfersNameTheirFiles() {
        assertEquals(listOf(TerminalPlanStep.AdbPull("/sdcard/a.txt", "a.txt")), steps("adb pull /sdcard/a.txt"))
        assertEquals(listOf(TerminalPlanStep.AdbPush("a.txt", "/sdcard/a.txt")), steps("adb push a.txt /sdcard/"))
        assertEquals(listOf(TerminalPlanStep.AdbPush("a.txt", "/sdcard/b.txt")), steps("adb push a.txt /sdcard/b.txt"))
        assertIs<TerminalPlanResult.NotImplemented>(compiler.compile("adb push a b /sdcard/"))
        assertIs<TerminalPlanResult.NotImplemented>(compiler.compile("adb pull -a /sdcard/a.txt"))
    }

    @Test
    fun installPassesPackageFlagsAndSplitsCombinedOnes() {
        assertEquals(listOf(TerminalPlanStep.AdbInstall(listOf("app.apk"), listOf("-r", "-g"), false)),
            steps("adb install -rg app.apk"))
        assertEquals(listOf(TerminalPlanStep.AdbInstall(listOf("a.apk", "b.apk"), listOf("--abi", "arm64-v8a"), true)),
            steps("adb install-multiple --streaming --abi arm64-v8a a.apk b.apk"))
        assertEquals(TerminalFileNeed.OPEN_MANY, steps("adb install-multiple a.apk").single().fileNeed)
    }

    @Test
    fun setActiveWithoutASlotMeansTheOtherOne() {
        assertEquals(
            listOf(TerminalPlanStep.FastbootFlashImage("boot", "boot.img"), TerminalPlanStep.FastbootWire(SET_ACTIVE_OTHER)),
            steps("fastboot --set-active flash boot boot.img"),
        )
        assertEquals(listOf(TerminalPlanStep.FastbootWire(SET_ACTIVE_OTHER)), steps("fastboot --set-active"))
    }

    @Test
    fun verityFlagsPatchTheVbmetaImageOnly() {
        assertEquals(
            listOf(TerminalPlanStep.FastbootFlashImage("vbmeta", "vbmeta.img", vbmetaFlags = 3)),
            steps("fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img"),
        )
        assertIs<TerminalPlanResult.NotImplemented>(compiler.compile("fastboot --disable-verity flash boot boot.img"))
    }

    @Test
    fun slotRidesOnFlashAndErase() {
        assertEquals(listOf(TerminalPlanStep.FastbootFlashImage("boot", "boot.img", "a")),
            steps("fastboot --slot a flash boot boot.img"))
        assertEquals(listOf(TerminalPlanStep.FastbootWire("erase:cache", "all")), steps("fastboot --slot=all erase cache"))
    }

    @Test
    fun anUnknownAdbVerbIsNotTurnedIntoShell() {
        assertIs<TerminalPlanResult.NotImplemented>(compiler.compile("adb getprop ro.product.device"))
    }
}
