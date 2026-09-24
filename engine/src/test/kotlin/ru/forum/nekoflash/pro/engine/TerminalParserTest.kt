package ru.forum.nekoflash.pro.engine

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
}
