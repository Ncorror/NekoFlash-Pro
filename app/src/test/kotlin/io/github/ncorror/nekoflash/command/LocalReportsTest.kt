package io.github.ncorror.nekoflash.command

import com.ncorror.nekoflash.pro.engine.LocalReportKind
import io.github.ncorror.nekoflash.adb.AdbLinkState
import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.fastboot.FastbootLinkState
import io.github.ncorror.nekoflash.protocol.adb.AdbHandshakeFailure
import io.github.ncorror.nekoflash.protocol.adb.AdbPeerMode
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootIdentity
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockState
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLockStatus
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootMode
import io.github.ncorror.nekoflash.usb.api.TargetIdentitySource
import io.github.ncorror.nekoflash.usb.api.UsbTargetIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/** `devices`, `get-state` и `status` — строками настольного клиента, из состояния связи. */
class LocalReportsTest {
    private val generation = SessionGeneration(7)
    private val phone = UsbTargetIdentity(
        id = TargetId("serial:ABC123"),
        source = TargetIdentitySource.SERIAL,
        vendorId = 0x18d1,
        productId = 0x4ee7,
        serialNumber = "ABC123",
        attachmentName = "/dev/bus/usb/001/002",
    )
    private val known = { g: SessionGeneration -> phone.takeIf { g == generation } }
    private val connected = AdbLinkState.Connected(
        generation = generation,
        peerMode = AdbPeerMode.DEVICE,
        banner = "device::ro.product.name=sky;ro.product.model=Redmi Note 12;ro.product.device=sky;features=shell_v2,cmd",
        features = setOf("shell_v2", "cmd"),
    )

    @Test
    fun devicesListsTheLinkTheAppHolds() {
        assertEquals("List of devices attached\nABC123\tdevice", LocalReports.adb(LocalReportKind.DEVICES, connected, known))
        assertEquals(
            "List of devices attached\nABC123                 device usb:/dev/bus/usb/001/002" +
                " product:sky model:Redmi_Note_12 device:sky transport_id:7",
            LocalReports.adb(LocalReportKind.DEVICES_LONG, connected, known),
        )
    }

    @Test
    fun stateNamesTheModeTheBannerAnnounced() {
        assertEquals("device", LocalReports.adb(LocalReportKind.GET_STATE, connected, known))
        val recovery = connected.copy(peerMode = AdbPeerMode.RECOVERY, banner = "recovery::")
        assertEquals("recovery", LocalReports.adb(LocalReportKind.GET_STATE, recovery, known))
        val rescue = connected.copy(peerMode = AdbPeerMode.UNKNOWN, banner = "rescue::")
        assertEquals("rescue", LocalReports.adb(LocalReportKind.GET_STATE, rescue, known))
        assertEquals(
            "List of devices attached\nABC123\tunauthorized",
            LocalReports.adb(LocalReportKind.DEVICES, AdbLinkState.WaitingForAuthorization(generation), known),
        )
    }

    @Test
    fun withoutALinkTheListIsEmptyAndStateIsAnError() {
        val lost = AdbLinkState.Failed(generation, AdbHandshakeFailure.TRANSPORT_CLOSED, "gone", afterConnect = true)
        assertEquals("List of devices attached", LocalReports.adb(LocalReportKind.DEVICES, lost, known))
        assertEquals("error: no devices/emulators found", LocalReports.adb(LocalReportKind.GET_STATE, AdbLinkState.Idle, known))
        assertEquals("adb: connection lost: gone", LocalReports.adb(LocalReportKind.STATUS, lost, known))
        assertEquals("adb: no connection", LocalReports.adb(LocalReportKind.STATUS, AdbLinkState.Idle, known))
    }

    @Test
    fun anUnreadableSerialIsWrittenAsTheDesktopClientWritesIt() {
        val nobody = { _: SessionGeneration -> null }
        assertEquals("List of devices attached\n????????????\tdevice", LocalReports.adb(LocalReportKind.DEVICES, connected, nobody))
    }

    @Test
    fun fastbootListsItsLinkWithoutAHeader() {
        val link = FastbootLinkState.Connected(
            generation = generation,
            identity = FastbootIdentity(FastbootMode.BOOTLOADER, "is-userspace=no"),
            lock = FastbootLockStatus(FastbootLockState.UNLOCKED, "unlocked=yes"),
        )
        assertEquals("ABC123\tfastboot", LocalReports.fastboot(LocalReportKind.DEVICES, link, known))
        assertEquals("", LocalReports.fastboot(LocalReportKind.DEVICES, FastbootLinkState.Idle, known))
        assertEquals("fastboot: bootloader ABC123, lock unlocked", LocalReports.fastboot(LocalReportKind.STATUS, link, known))
    }
}
