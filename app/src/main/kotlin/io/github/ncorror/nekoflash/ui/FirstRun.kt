package io.github.ncorror.nekoflash.ui

import android.content.Context

/**
 * Whether the current app process already passed the startup Welcome screen.
 *
 * This is deliberately process-local rather than persistent. A cold process
 * start always begins at Welcome; Activity recreation or returning from the
 * background in the same process does not show it again.
 */
internal class FirstRun(context: Context) {
    private val processKey = context.applicationContext.packageName

    var seen: Boolean
        get() = processKey in seenProcesses
        set(value) {
            if (value) seenProcesses += processKey else seenProcesses -= processKey
        }

    private companion object {
        val seenProcesses = mutableSetOf<String>()
    }
}
