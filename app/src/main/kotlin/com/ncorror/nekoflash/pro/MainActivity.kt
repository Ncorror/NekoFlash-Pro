package com.ncorror.nekoflash.pro

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.hardware.usb.UsbManager
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ncorror.nekoflash.pro.diagnostics.DiagnosticArchive
import com.ncorror.nekoflash.pro.engine.TerminalPlanStep
import com.ncorror.nekoflash.pro.model.AdbProbePhase
import com.ncorror.nekoflash.pro.model.AdbProbeKind
import com.ncorror.nekoflash.pro.model.AdbProbeState
import com.ncorror.nekoflash.pro.model.FastbootProbePhase
import com.ncorror.nekoflash.pro.model.FastbootProbeState
import com.ncorror.nekoflash.pro.model.FastbootFlashState
import com.ncorror.nekoflash.pro.model.FastbootFlashPhase
import com.ncorror.nekoflash.pro.model.SelectedImage
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.platform.usb.AdbProbeController
import com.ncorror.nekoflash.pro.platform.usb.FastbootProbeController
import com.ncorror.nekoflash.pro.platform.usb.FastbootFlashController
import com.ncorror.nekoflash.pro.platform.usb.UsbHostController
import com.ncorror.nekoflash.pro.ui.NekoFlashTheme
import com.ncorror.nekoflash.pro.ui.UsbScreen
import com.ncorror.nekoflash.pro.transport.UsbAutoAction
import com.ncorror.nekoflash.pro.transport.UsbAutoCandidate
import com.ncorror.nekoflash.pro.transport.UsbAutoConnectPlanner
import com.ncorror.nekoflash.pro.transport.UsbSessionGate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Android lifecycle entry point. USB policy and display live in separate packages. */
class MainActivity : ComponentActivity() {
    private var usbSnapshot by mutableStateOf(UsbHostSnapshot())
    private var adbProbeState by mutableStateOf<AdbProbeState?>(null)
    private var adbFirstReplyState by mutableStateOf<AdbProbeState?>(null)
    private var adbProductState by mutableStateOf<AdbProbeState?>(null)
    private var probeState by mutableStateOf<FastbootProbeState?>(null)
    private var flashState by mutableStateOf<FastbootFlashState?>(null)
    private var selectedImage by mutableStateOf<SelectedImage?>(null)
    private var selectedImageUri: Uri? = null
    private var selectionGeneration = 0L
    private var exportNotice by mutableStateOf<Int?>(null)
    private lateinit var usbHost: UsbHostController
    private lateinit var adbProbeController: AdbProbeController
    private lateinit var probeController: FastbootProbeController
    private lateinit var flashController: FastbootFlashController
    private val autoConnect = UsbAutoConnectPlanner()
    private val usbSessionGate = UsbSessionGate()
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

    private val chooseImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        // OpenDocument supplies a content Uri; its name and Uri are never written to diagnostics.
        val metadata = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex < 0 || sizeIndex < 0 || cursor.isNull(nameIndex) || cursor.isNull(sizeIndex)) {
                    return@use null
                }
                cursor.getString(nameIndex) to cursor.getLong(sizeIndex)
            }
        }.getOrNull()
        val name = metadata?.first?.replace(Regex("[\\p{Cntrl}]+"), " ")?.take(128)
        val size = metadata?.second
        if (name.isNullOrBlank() || size == null || size !in 1..0xffff_ffffL) {
            diagnostics.record("Fastboot image selection rejected: missing name or unsupported size")
            selectedImageUri = null
            selectedImage = null
            return@registerForActivityResult
        }
        selectedImageUri = uri
        selectedImage = SelectedImage(name, size, ++selectionGeneration)
        diagnostics.record("Fastboot raw image selected: bytes=$size")
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
        adbProbeController = AdbProbeController(this, usbSessionGate, diagnostics::record) { state ->
            when (state.kind) {
                AdbProbeKind.CONNECTION -> adbProbeState = state
                AdbProbeKind.FIRST_REPLY -> adbFirstReplyState = state
                AdbProbeKind.PRODUCT -> adbProductState = state
            }
        }
        probeController = FastbootProbeController(this, usbSessionGate, diagnostics::record) { probeState = it }
        flashController = FastbootFlashController(this, usbSessionGate, diagnostics::record) { flashState = it }
        usbHost = UsbHostController(this, { snapshot ->
            val connectionGone = adbProbeState?.let { state ->
                snapshot.devices.none(state::matches)
            } == true
            val diagnosticGone = adbFirstReplyState?.let { state ->
                snapshot.devices.none(state::matches)
            } == true
            val productGone = adbProductState?.let { state ->
                snapshot.devices.none(state::matches)
            } == true
            if (connectionGone && adbProbeState?.phase in setOf(AdbProbePhase.AUTHORIZING,
                    AdbProbePhase.WAITING_CONFIRMATION) ||
                diagnosticGone && adbFirstReplyState?.phase == AdbProbePhase.RUNNING ||
                productGone && adbProductState?.phase in setOf(AdbProbePhase.AUTHORIZING,
                    AdbProbePhase.WAITING_CONFIRMATION)) {
                adbProbeController.cancel()
            }
            if (connectionGone) adbProbeState = null
            if (diagnosticGone) adbFirstReplyState = null
            if (productGone) adbProductState = null
            if (probeState?.let { state -> snapshot.devices.none(state::matches) } == true) {
                probeController.cancel()
                probeState = null
            }
            if (flashState?.phase == FastbootFlashPhase.RUNNING &&
                flashState?.let { state -> snapshot.devices.none(state::matches) } == true) {
                flashController.cancel()
                flashState = flashState?.copy(phase = FastbootFlashPhase.CANCELLED)
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
            if (adbFirstReplyState?.deviceKey == key && adbFirstReplyState?.deviceId == id) {
                adbProbeController.cancel()
                adbFirstReplyState = null
            }
            if (adbProductState?.deviceKey == key && adbProductState?.deviceId == id) {
                adbProbeController.cancel()
                adbProductState = null
            }
            if (probeState?.deviceKey == key && probeState?.deviceId == id) {
                probeController.cancel()
                probeState = null
            }
            if (flashState?.deviceKey == key && flashState?.deviceId == id &&
                flashState?.phase == FastbootFlashPhase.RUNNING) {
                flashController.cancel()
                flashState = flashState?.copy(phase = FastbootFlashPhase.CANCELLED)
            }
        })
        setContent {
            NekoFlashTheme {
                UsbScreen(
                    snapshot = usbSnapshot,
                    adbProbeState = adbProbeState,
                    adbFirstReplyState = adbFirstReplyState,
                    adbProductState = adbProductState,
                    probeState = probeState,
                    flashState = flashState,
                    onRefresh = {
                        diagnostics.record("User refreshed USB devices")
                        usbHost.refresh()
                    },
                    onRequestAccess = usbHost::requestAccess,
                    onProbeAdb = { device ->
                        if (probeState?.phase != FastbootProbePhase.RUNNING &&
                            flashState?.phase != FastbootFlashPhase.RUNNING) adbProbeController.probe(device)
                    },
                    onAuthorizeAdb = { device ->
                        if (probeState?.phase != FastbootProbePhase.RUNNING &&
                            flashState?.phase != FastbootFlashPhase.RUNNING) adbProbeController.authorize(device)
                    },
                    onReadAdbProduct = { device ->
                        if (probeState?.phase != FastbootProbePhase.RUNNING &&
                            flashState?.phase != FastbootFlashPhase.RUNNING) adbProbeController.readProduct(device)
                    },
                    onCancelAdbAuthorization = {
                        diagnostics.record("User cancelled ADB authorization")
                        adbProbeController.cancel()
                        adbProbeState = null
                        adbProductState = null
                    },
                    onProbeFastboot = { device, variable ->
                        if (adbProbeState?.phase !in setOf(AdbProbePhase.RUNNING,
                                AdbProbePhase.AUTHORIZING, AdbProbePhase.WAITING_CONFIRMATION) &&
                            adbFirstReplyState?.phase != AdbProbePhase.RUNNING &&
                            adbProductState?.phase !in setOf(AdbProbePhase.AUTHORIZING,
                                AdbProbePhase.WAITING_CONFIRMATION) &&
                            flashState?.phase != FastbootFlashPhase.RUNNING) {
                            probeController.probe(device, variable)
                        }
                    },
                    onRunPlan = { device, plan ->
                        val adbBusy = adbProbeState?.phase in setOf(AdbProbePhase.RUNNING,
                            AdbProbePhase.AUTHORIZING, AdbProbePhase.WAITING_CONFIRMATION) ||
                            adbFirstReplyState?.phase == AdbProbePhase.RUNNING ||
                            adbProductState?.phase in setOf(AdbProbePhase.AUTHORIZING,
                                AdbProbePhase.WAITING_CONFIRMATION)
                        if (!adbBusy && probeState?.phase != FastbootProbePhase.RUNNING &&
                            flashState?.phase != FastbootFlashPhase.RUNNING &&
                            usbSnapshot.devices.singleOrNull()?.let { it.key == device.key &&
                                it.deviceId == device.deviceId && it.vendorId == device.vendorId &&
                                it.productId == device.productId && it.accessGranted } == true) {
                            when (plan.steps.singleOrNull()) {
                                is TerminalPlanStep.FastbootGetvar -> probeController.probePlan(device, plan)
                                TerminalPlanStep.AdbProduct -> adbProbeController.readProductPlan(device, plan)
                                is TerminalPlanStep.FastbootFlashRaw -> {
                                    val step = plan.steps.single() as TerminalPlanStep.FastbootFlashRaw
                                    val picked = selectedImage
                                    val uri = selectedImageUri
                                    if (picked != null && uri != null &&
                                        picked.selectionId == step.selectionId && picked.size == step.imageSize) {
                                        flashController.flash(device, step, uri)
                                    }
                                }
                                else -> Unit
                            }
                        }
                    },
                    selectedImage = selectedImage,
                    onSelectImage = { chooseImage.launch(arrayOf("*/*")) },
                    onCancelFlash = {
                        flashController.cancel()
                        flashState = flashState?.copy(phase = FastbootFlashPhase.CANCELLED)
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
        adbFirstReplyState = null
        adbProductState = null
        probeController.cancel()
        probeState = null
        if (flashState?.phase == FastbootFlashPhase.RUNNING) {
            flashController.cancel()
            flashState = flashState?.copy(phase = FastbootFlashPhase.CANCELLED)
        }
        usbHost.stop()
        super.onStop()
    }

    private fun timestamp(): String = LocalDateTime.now().format(
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"),
    )
}
