package com.ncorror.nekoflash.pro

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ncorror.nekoflash.pro.diagnostics.DiagnosticArchive
import com.ncorror.nekoflash.pro.model.FastbootProbeState
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.platform.usb.FastbootProbeController
import com.ncorror.nekoflash.pro.platform.usb.UsbHostController
import com.ncorror.nekoflash.pro.ui.NekoFlashTheme
import com.ncorror.nekoflash.pro.ui.UsbScreen
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Android lifecycle entry point. USB policy and display live in separate packages. */
class MainActivity : ComponentActivity() {
    private var usbSnapshot by mutableStateOf(UsbHostSnapshot())
    private var probeState by mutableStateOf<FastbootProbeState?>(null)
    private var exportNotice by mutableStateOf<Int?>(null)
    private lateinit var usbHost: UsbHostController
    private lateinit var probeController: FastbootProbeController
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
        probeController = FastbootProbeController(this, diagnostics::record) { probeState = it }
        usbHost = UsbHostController(this, { snapshot ->
            if (probeState?.let { state -> snapshot.devices.none(state::matches) } == true) {
                probeController.cancel()
                probeState = null
            }
            usbSnapshot = snapshot
        }, diagnostics::record)
        setContent {
            NekoFlashTheme {
                UsbScreen(
                    snapshot = usbSnapshot,
                    probeState = probeState,
                    onRefresh = {
                        diagnostics.record("User refreshed USB devices")
                        usbHost.refresh()
                    },
                    onRequestAccess = usbHost::requestAccess,
                    onProbeFastboot = probeController::probe,
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

    override fun onResume() {
        super.onResume()
        diagnostics.record("App resumed")
        usbHost.refresh()
    }

    override fun onStop() {
        probeController.cancel()
        probeState = null
        usbHost.stop()
        super.onStop()
    }

    private fun timestamp(): String = LocalDateTime.now().format(
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"),
    )
}
