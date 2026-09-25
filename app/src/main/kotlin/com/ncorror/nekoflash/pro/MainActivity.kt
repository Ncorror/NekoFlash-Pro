package com.ncorror.nekoflash.pro

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ncorror.nekoflash.pro.diagnostics.DiagnosticArchive
import com.ncorror.nekoflash.pro.model.AdbProbePhase
import com.ncorror.nekoflash.pro.model.AdbProbeState
import com.ncorror.nekoflash.pro.model.FastbootProbePhase
import com.ncorror.nekoflash.pro.model.FastbootProbeState
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.platform.usb.AdbProbeController
import com.ncorror.nekoflash.pro.platform.usb.FastbootProbeController
import com.ncorror.nekoflash.pro.platform.usb.UsbHostController
import com.ncorror.nekoflash.pro.ui.NekoFlashTheme
import com.ncorror.nekoflash.pro.ui.UsbScreen
import com.ncorror.nekoflash.pro.transport.UsbAutoAction
import com.ncorror.nekoflash.pro.transport.UsbAutoCandidate
import com.ncorror.nekoflash.pro.transport.UsbAutoConnectPlanner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Android lifecycle entry point. USB policy and display live in separate packages. */
class MainActivity : ComponentActivity() {
    private var usbSnapshot by mutableStateOf(UsbHostSnapshot())
    private var adbProbeState by mutableStateOf<AdbProbeState?>(null)
    private var probeState by mutableStateOf<FastbootProbeState?>(null)
    private var exportNotice by mutableStateOf<Int?>(null)
    private lateinit var usbHost: UsbHostController
    private lateinit var adbProbeController: AdbProbeController
    private lateinit var probeController: FastbootProbeController
    private val autoConnect = UsbAutoConnectPlanner()
    private val diagnostics get() = (application as NekoFlashApplication).diagnostics
    private val archive by lazy { DiagnosticArchive(this, diagnostics) }

    private val createLogFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) exportFile(uri, "TXT", diagnostics::export)
    }

    private val createArchiveFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) exportFile(uri, "ZIP", archive::export)
    }

    private fun exportFile(uri: Uri, format: String, write: (Uri) -> Unit) {
        Thread({
            val result = runCatching {
                diagnostics.record("Diagnostic export started: $format")
                write(uri)
            }
            if (result.isFailure) {
                diagnostics.recordFailure("Diagnostic export failed", result.exceptionOrNull()!!)
            }
            runOnUiThread {
                exportNotice = if (result.isSuccess) R.string.diagnostics_saved
                    else R.string.diagnostics_save_failed
            }
        }, "diagnostic-export").start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics.record("App opened")
        adbProbeController = AdbProbeController(this, diagnostics::record) { adbProbeState = it }
        probeController = FastbootProbeController(this, diagnostics::record) { probeState = it }
        usbHost = UsbHostController(this, { snapshot ->
            if (adbProbeState?.let { state -> snapshot.devices.none(state::matches) } == true) {
                adbProbeController.cancel()
                adbProbeState = null
            }
            if (probeState?.let { state -> snapshot.devices.none(state::matches) } == true) {
                probeController.cancel()
                probeState = null
            }
            usbSnapshot = snapshot
            val candidates = snapshot.devices.map { device ->
                val usable = device.interfaces.filter {
                    it.configurationIndex == 0 && it.bulkPairAvailable
                }.singleOrNull()
                UsbAutoCandidate(device.key, device.deviceId, usable?.protocol,
                    device.accessGranted, device.accessPending)
            }
            when (val next = autoConnect.observe(candidates)) {
                is UsbAutoAction.RequestAccess -> {
                    diagnostics.record("Auto USB access request for recognized device")
                    usbHost.requestAccess(next.key)
                }
                is UsbAutoAction.AuthorizeAdb -> {
                    val device = snapshot.devices.singleOrNull {
                        it.key == next.key && it.deviceId == next.deviceId
                    }
                    if (device != null) {
                        diagnostics.record("Auto ADB authorization after USB access")
                        adbProbeController.authorize(device)
                    }
                }
                null -> Unit
            }
        }, diagnostics::record, { key, id ->
            autoConnect.forget(key, id)
            if (adbProbeState?.deviceKey == key && adbProbeState?.deviceId == id) {
                adbProbeController.cancel()
                adbProbeState = null
            }
            if (probeState?.deviceKey == key && probeState?.deviceId == id) {
                probeController.cancel()
                probeState = null
            }
        })
        setContent {
            NekoFlashTheme {
                UsbScreen(
                    snapshot = usbSnapshot,
                    adbProbeState = adbProbeState,
                    probeState = probeState,
                    onRefresh = {
                        diagnostics.record("User refreshed USB devices")
                        usbHost.refresh()
                    },
                    onRequestAccess = usbHost::requestAccess,
                    onProbeAdb = { device ->
                        if (probeState?.phase != FastbootProbePhase.RUNNING) adbProbeController.probe(device)
                    },
                    onAuthorizeAdb = { device ->
                        if (probeState?.phase != FastbootProbePhase.RUNNING) adbProbeController.authorize(device)
                    },
                    onCancelAdbAuthorization = {
                        diagnostics.record("User cancelled ADB authorization")
                        adbProbeController.cancel()
                        adbProbeState = null
                    },
                    onProbeFastboot = { device, variable ->
                        if (adbProbeState?.phase !in setOf(AdbProbePhase.RUNNING,
                                AdbProbePhase.AUTHORIZING, AdbProbePhase.WAITING_CONFIRMATION)) {
                            probeController.probe(device, variable)
                        }
                    },
                    onSaveLog = {
                        exportNotice = null
                        createLogFile.launch("NekoFlashPro-diagnostics-${timestamp()}.txt")
                    },
                    onSaveArchive = {
                        exportNotice = null
                        createArchiveFile.launch("NekoFlashPro-diagnostics-${timestamp()}.zip")
                    },
                    exportNotice = exportNotice,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        usbHost.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            diagnostics.record("USB attachment opened existing activity")
            usbHost.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        diagnostics.record("App resumed")
        usbHost.refresh()
    }

    override fun onStop() {
        adbProbeController.cancel()
        adbProbeState = null
        probeController.cancel()
        probeState = null
        usbHost.stop()
        super.onStop()
    }

    private fun timestamp(): String = LocalDateTime.now().format(
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"),
    )
}
