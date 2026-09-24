package ru.forum.nekoflash.pro

import android.app.Activity
import android.os.Bundle

/** The build can be installed without exposing commands before a tested USB runner exists. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
