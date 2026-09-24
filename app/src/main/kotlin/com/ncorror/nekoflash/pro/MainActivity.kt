package com.ncorror.nekoflash.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ncorror.nekoflash.pro.model.UsbHostSnapshot
import com.ncorror.nekoflash.pro.platform.usb.UsbHostController
import com.ncorror.nekoflash.pro.ui.NekoFlashTheme
import com.ncorror.nekoflash.pro.ui.UsbScreen

/** Android lifecycle entry point. USB policy and display live in separate packages. */
class MainActivity : ComponentActivity() {
    private var usbSnapshot by mutableStateOf(UsbHostSnapshot())
    private lateinit var usbHost: UsbHostController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbHost = UsbHostController(this) { usbSnapshot = it }
        setContent {
            NekoFlashTheme {
                UsbScreen(
                    snapshot = usbSnapshot,
                    onRefresh = usbHost::refresh,
                    onRequestAccess = usbHost::requestAccess,
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
