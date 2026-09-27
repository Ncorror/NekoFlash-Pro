package com.ncorror.nekoflash.pro.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TerminalParserTest {
    private val parser = TerminalParser()

    private fun fastboot(line: String): TerminalInvocation.Fastboot =
        assertIs<TerminalInvocation.Fastboot>(assertIs<TerminalParseResult.Parsed>(parser.parse(line)).invocation)

    private fun adb(line: String): TerminalInvocation.Adb =
        assertIs<TerminalInvocation.Adb>(assertIs<TerminalParseResult.Parsed>(parser.parse(line)).invocation)

    @Test
    fun formatIsAHostOperationIncludingOverrides() {
        val parsed = fastboot("fastboot format:f2fs:0x4000000 userdata")
        assertEquals(FastbootCommand.Format("userdata", "f2fs", "0x4000000"), parsed.command)
        assertEquals(FastbootCommand.Format("userdata", null, null), fastboot("fastboot format userdata").command)
    }

    @Test
    fun wipeAndSetActiveSurviveCombinationWithFlash() {
        val parsed = fastboot("fastboot --set-active=a -w --slot=other flash boot boot.img")
        assertEquals(FastbootCommand.Flash("boot", "boot.img"), parsed.command)
        assertEquals("a", parsed.options.setActiveSlot)
        assertTrue(parsed.options.setActiveRequested)
        assertEquals("other", parsed.options.slot)
        assertTrue(parsed.options.wipe)
        assertEquals(FastbootCommand.Wipe, fastboot("fastboot -w").command)
    }

    @Test
    fun sparseAndForceOptionsArePreserved() {
        val parsed = fastboot("fastboot -S 512M --force --disable-verity --disable-verification flash vbmeta vbmeta.img")
        assertEquals("512M", parsed.options.sparseLimit)
        assertTrue(parsed.options.force)
        assertTrue(parsed.options.disableVerity)
        assertTrue(parsed.options.disableVerification)
    }

    @Test
    fun wipeSuperAndArchiveFlashingAreHostOperations() {
        assertEquals(FastbootCommand.WipeSuper("super_empty.img"), fastboot("fastboot wipe-super super_empty.img").command)
        assertEquals(FastbootCommand.Update("ota.zip"), fastboot("fastboot update ota.zip").command)
        assertEquals(FastbootCommand.FlashAll, fastboot("fastboot flashall").command)
        assertEquals(FastbootCommand.UpdateSuper("super.img", true),
            fastboot("fastboot update-super super.img --wipe").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("fastboot update-super super.img wipe extra"))
        assertIs<TerminalParseResult.Unsupported>(parser.parse("fastboot update-super super.img unexpected"))
    }

    @Test
    fun multiPartBootInputIsPreserved() {
        assertEquals(FastbootCommand.Boot("kernel", "ramdisk", "second"),
            fastboot("fastboot boot kernel ramdisk second").command)
        assertEquals(FastbootCommand.FlashRaw("boot", "kernel", "ramdisk", null),
            fastboot("fastboot flash:raw boot kernel ramdisk").command)
    }

    @Test
    fun vendorBootRamdiskIsAnImageRepackOperation() {
        assertEquals(FastbootCommand.FlashVendorBootRamdisk("default", "new_ramdisk.img"),
            fastboot("fastboot flash vendor_boot:default new_ramdisk.img").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("fastboot flash vendor_boot: ramdisk.img"))
    }

    @Test
    fun optionalSetActiveArgumentMustBeAttached() {
        val parsed = fastboot("fastboot --set-active=a flash boot boot.img")
        assertEquals("a", parsed.options.setActiveSlot)
        assertEquals(FastbootCommand.Flash("boot", "boot.img"), parsed.command)
        // A bare --set-active does not take the next positional argument as its slot.
        assertEquals(FastbootCommand.SetActive(null), fastboot("fastboot --set-active").command)
    }

    @Test
    fun sparseSizeMustNotAbsorbFollowingFlag() {
        assertIs<TerminalParseResult.Invalid>(parser.parse("fastboot -S --force flash boot boot.img"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("fastboot -S unlimited flash boot boot.img"))
        assertEquals("0", fastboot("fastboot -S0 flash system system.img").options.sparseLimit)
    }

    @Test
    fun sideloadAndUninstallHaveDedicatedAdbSemantics() {
        assertEquals(AdbCommand.Sideload("ota.zip"), adb("adb sideload ota.zip").command)
        assertEquals(AdbCommand.Uninstall("com.example.app", true), adb("adb uninstall -k com.example.app").command)
        assertEquals(AdbCommand.Remount(true), adb("adb remount -R").command)
    }

    @Test
    fun oneApkIsValidForInstallMultiple() {
        assertEquals(AdbCommand.InstallMultiple(listOf("base.apk"), listOf("-r")),
            adb("adb install-multiple -r base.apk").command)
        assertEquals(AdbCommand.InstallMultiple(listOf("base.custom"), listOf("--abi", "arm64-v8a")),
            adb("adb install-multiple --abi arm64-v8a base.custom").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("adb install -p base.apk"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb install --abi -r base.apk"))
    }

    @Test
    fun transferOptionsRemainOptionsAndPullCanUseDefaultDestination() {
        assertEquals(AdbCommand.Push(listOf("one", "two"), "/sdcard/", FileTransferOptions(
            syncOnly = true, compression = "zstd",
        )), adb("adb push --sync -z zstd one two /sdcard/").command)
        assertEquals(AdbCommand.Pull(listOf("/sdcard/example"), ".", FileTransferOptions(
            preserveAttributes = true,
        )), adb("adb pull -a /sdcard/example").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb push -z -q local /sdcard/"))
    }

    @Test
    fun tcpipNeedsAnExplicitValidPort() {
        assertEquals(AdbCommand.Service("tcpip:5555"), adb("adb tcpip 5555").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb tcpip"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb tcpip 99999"))
    }

    @Test
    fun syncArgumentsHaveHostSemantics() {
        assertEquals(AdbCommand.Sync("vendor", FileTransferOptions(dryRun = true, compression = "zstd"), true),
            adb("adb sync -l -n -z zstd vendor").command)
        assertEquals(AdbCommand.Sync(null, FileTransferOptions(), false), adb("adb sync").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("adb sync vendor system"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb sync -z -q vendor"))
    }

    @Test
    fun unknownHostCommandsCannotTurnIntoRemoteShell() {
        assertIs<TerminalParseResult.Unsupported>(parser.parse("adb forward tcp:1234 tcp:1234"))
        assertIs<TerminalParseResult.Unsupported>(parser.parse("adb bugreport"))
        assertIs<TerminalParseResult.Unsupported>(parser.parse("adb shell -e ^ echo hi"))
        assertIs<TerminalParseResult.Unsupported>(parser.parse("fastboot --unrecognized flash boot boot.img"))
    }

    @Test
    fun explicitRawIsPreservedLiterally() {
        assertEquals(FastbootCommand.Getvar("product"), fastboot("fastboot getvar product").command)
        assertEquals(FastbootCommand.Getvar("all"), fastboot("fastboot getvar").command)
        assertEquals(FastbootCommand.Wire("getvar:product"), fastboot("fastboot raw getvar:product").command)
        assertEquals(FastbootCommand.Wire("oem special:token --strange"),
            fastboot("fastboot raw oem special:token --strange").command)
        assertEquals(AdbCommand.Service("shell:echo 'a b'"),
            adb("adb raw shell:echo 'a b'").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("fastboot service erase:userdata"))
        assertEquals(FastbootCommand.Wire("vendor-command:one two"),
            fastboot("fastboot vendor-command:one two").command)
        assertEquals(FastbootCommand.Wire("oem custom --vendor-flag"),
            fastboot("fastboot oem custom --vendor-flag").command)
        assertEquals(FastbootCommand.Wire("vendor-command --vendor-flag"),
            fastboot("fastboot vendor-command --vendor-flag").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("fastboot stage payload.bin"))
    }

    @Test
    fun quotedArgumentsDoNotLoseSpaces() {
        assertEquals(FastbootCommand.Flash("boot", "my images/boot.img"),
            fastboot("fastboot flash boot 'my images/boot.img'").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb push 'unclosed /sdcard/"))
    }

    @Test
    fun terminalTabsAcceptUnprefixedCommands() {
        val fastboot = assertIs<TerminalParseResult.Parsed>(
            parser.parse(TerminalParser.Tool.FASTBOOT, "format userdata")
        )
        assertEquals(FastbootCommand.Format("userdata", null, null),
            assertIs<TerminalInvocation.Fastboot>(fastboot.invocation).command)
        val adb = assertIs<TerminalParseResult.Parsed>(
            parser.parse(TerminalParser.Tool.ADB, "sideload update.zip")
        )
        assertEquals(AdbCommand.Sideload("update.zip"),
            assertIs<TerminalInvocation.Adb>(adb.invocation).command)
    }

    @Test
    fun statusCommandsDoNotBecomeOneAnother() {
        assertEquals(AdbCommand.GetState, adb("adb get-state").command)
        assertEquals(AdbCommand.Devices(true), adb("adb devices -l").command)
        assertEquals(FastbootCommand.Devices(true), fastboot("fastboot devices -l").command)
    }

    @Test
    fun fastbootWireFormsKeepTheirArguments() {
        assertEquals(FastbootCommand.Wire("reboot"), fastboot("fastboot reboot").command)
        assertEquals(FastbootCommand.Wire("reboot-recovery"), fastboot("fastboot reboot recovery").command)
        assertEquals(FastbootCommand.Wire("reboot-fastboot"), fastboot("fastboot reboot fastboot").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("fastboot reboot edl"))
        assertEquals(FastbootCommand.Wire("oem device-info"), fastboot("fastboot oem device-info").command)
        assertEquals(FastbootCommand.Wire("flashing unlock_critical"),
            fastboot("fastboot flashing unlock_critical").command)
        assertEquals(FastbootCommand.Wire("gsi:wipe"), fastboot("fastboot gsi wipe").command)
        assertEquals(FastbootCommand.Wire("snapshot-update"), fastboot("fastboot snapshot-update").command)
        assertEquals(FastbootCommand.Wire("snapshot-update:cancel"), fastboot("fastboot snapshot-update cancel").command)
        assertEquals(FastbootCommand.Wire("create-logical-partition:foo:4096"),
            fastboot("fastboot create-logical-partition foo 4096").command)
        assertEquals(FastbootCommand.Wire("delete-logical-partition:foo"),
            fastboot("fastboot delete-logical-partition foo").command)
        assertEquals(FastbootCommand.DeviceQuery("is-logical", "system"), fastboot("fastboot is-logical system").command)
    }

    @Test
    fun fastbootPartitionCommands() {
        assertEquals(FastbootCommand.Erase("cache"), fastboot("fastboot erase cache").command)
        assertEquals(FastbootCommand.Fetch("vendor_boot", "out.img"), fastboot("fastboot fetch vendor_boot out.img").command)
        assertEquals(FastbootCommand.SetActive("b"), fastboot("fastboot set_active b").command)
        assertEquals(FastbootCommand.SetActive("a"), fastboot("fastboot set-active a").command)
        assertEquals(FastbootCommand.Flash("boot", null), fastboot("fastboot flash boot").command)
        assertEquals(FastbootCommand.Boot("boot.img", null, null), fastboot("fastboot boot boot.img").command)
        assertEquals(FastbootCommand.LocalStatus, fastboot("fastboot status").command)
        assertEquals(FastbootCommand.Devices(false), fastboot("fastboot devices").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("fastboot erase"))
        assertIs<TerminalParseResult.Unsupported>(parser.parse("fastboot flash"))
    }

    @Test
    fun fastbootOptionsInEveryForm() {
        val parsed = fastboot("fastboot -s SERIAL -v --slot b --skip-reboot --skip-secondary flash boot b.img")
        assertEquals("SERIAL", parsed.options.serial)
        assertTrue(parsed.options.verbose)
        assertEquals("b", parsed.options.slot)
        assertTrue(parsed.options.skipReboot)
        assertTrue(parsed.options.skipSecondary)
        assertEquals("a", fastboot("fastboot -a a flash boot b.img").options.setActiveSlot)
        assertEquals(FastbootCommand.SetActive("b"), fastboot("fastboot --set-active=b").command)
        assertEquals("1G", fastboot("fastboot -S1G flash system s.img").options.sparseLimit)
        assertIs<TerminalParseResult.Invalid>(parser.parse("fastboot --slot"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("fastboot --slot= flash boot b.img"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("fastboot"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("fastboot --verbose"))
        assertEquals(FastbootCommand.Flash("-odd", "b.img"), fastboot("fastboot flash -- -odd b.img").command)
    }

    @Test
    fun adbServicesAndShellForms() {
        assertEquals(AdbCommand.Shell("", null, false), adb("adb shell").command)
        assertEquals(AdbCommand.Shell("ls -l /", null, false), adb("adb shell ls -l /").command)
        assertEquals(AdbCommand.Shell("id", "-t", false), adb("adb shell -t id").command)
        assertEquals(AdbCommand.Shell("id", null, true), adb("adb shell -x id").command)
        assertEquals(AdbCommand.Shell("-n", null, false), adb("adb shell -- -n").command)
        assertEquals(AdbCommand.Exec("cat /x"), adb("adb exec cat /x").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb exec"))
        assertEquals(AdbCommand.Logcat(listOf("-d", "-s", "Tag")), adb("adb logcat -d -s Tag").command)
        assertEquals(AdbCommand.Service("reboot:"), adb("adb reboot").command)
        assertEquals(AdbCommand.Service("reboot:bootloader"), adb("adb reboot bootloader").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb reboot a b"))
        assertEquals(AdbCommand.Service("root:"), adb("adb root").command)
        assertEquals(AdbCommand.Service("usb:"), adb("adb usb").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("adb root now"))
        assertEquals(AdbCommand.Remount(false), adb("adb remount").command)
        assertIs<TerminalParseResult.Unsupported>(parser.parse("adb remount -x"))
        assertEquals(AdbCommand.Install("app.apk", listOf("-r", "-g")), adb("adb install -r -g app.apk").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb install a.apk b.apk"))
        assertEquals(AdbCommand.Uninstall("com.x", false), adb("adb uninstall com.x").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb uninstall"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb"))
        assertIs<TerminalParseResult.Invalid>(parser.parse("adb status now"))
        assertEquals(AdbCommand.LocalStatus, adb("adb status").command)
        assertIs<TerminalParseResult.Invalid>(parser.parse("git status"))
    }

    @Test
    fun tokensHonourQuotesAndEscapes() {
        assertEquals(FastbootCommand.Flash("boot", "a b.img"), fastboot("fastboot flash boot a\\ b.img").command)
        assertEquals(FastbootCommand.Flash("boot", "it's.img"), fastboot("fastboot flash boot \"it's.img\"").command)
        assertEquals(AdbCommand.Pull(listOf("/sdcard/x y"), ".", FileTransferOptions()),
            adb("adb pull \"/sdcard/x y\"").command)
    }
}

