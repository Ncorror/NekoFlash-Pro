package io.github.ncorror.nekoflash.ui

/** Welcome is shown once per app process and again after a cold process start. */
internal object FirstRun {
    var seen: Boolean = false
}
