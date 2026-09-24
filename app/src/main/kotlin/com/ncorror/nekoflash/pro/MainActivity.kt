package com.ncorror.nekoflash.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.platform.usb.UsbHostController
import com.ncorror.nekoflash.pro.ui.NekoFlashTheme
import com.ncorror.nekoflash.pro.ui.UsbScreen
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Android lifecycle entry point. USB policy and display live in separate packages. */
class MainActivity : ComponentActivity() {
    private var usbSnapshot by mutableStateOf(UsbHostSnapshot())
    private var exportNotice by mutableStateOf<Int?>(null)
    private lateinit var usbHost: UsbHostController
    private val diagnostics get() = (application as NekoFlashApplication).diagnostics

    private val createLogFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            Thread({
                val result = runCatching { diagnostics.export(uri) }
                if (result.isFailure) {
                    diagnostics.recordFailure("Diagnostic export failed", result.exceptionOrNull()!!)
                }
                runOnUiThread {
                    exportNotice = if (result.isSuccess) R.string.diagnostics_saved
                        else R.string.diagnostics_save_failed
                }
            }, "diagnostic-export").start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics.record("App opened")
        usbHost = UsbHostController(this, { usbSnapshot = it }, diagnostics::record)
        setContent {
            NekoFlashTheme {
                UsbScreen(
                    snapshot = usbSnapshot,
                    onRefresh = usbHost::refresh,
                    onRequestAccess = usbHost::requestAccess,
                    onSaveLog = {
                        exportNotice = null
                        val timestamp = LocalDateTime.now().format(
                            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"),
                        )
                        createLogFile.launch("NekoFlashPro-diagnostics-$timestamp.txt")
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
        usbHost.refresh()
    }

    override fun onStop() {
        usbHost.stop()
        super.onStop()
    }
}
