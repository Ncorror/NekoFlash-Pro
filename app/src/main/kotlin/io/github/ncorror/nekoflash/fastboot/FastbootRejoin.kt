package io.github.ncorror.nekoflash.fastboot

import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane

/**
 * Возвращение к той же цели после её перезагрузки посреди сценария —
 * `reboot_to_userspace_fastboot` настольного клиента: `WaitForDisconnect`,
 * затем `open_device` к новому подключению.
 *
 * [possible] — можно ли узнать цель после переподключения: только по
 * серийному номеру; без него «то же устройство» доказать нечем, и сценарий
 * отказывается до первой команды.
 */
internal interface FastbootRejoin {
    val possible: Boolean

    /** Полоса нового подключения той же цели или `null`, если оно не пришло. */
    fun await(): FastbootLane?

    companion object {
        /** Возвращаться не к чему: сценарий, которому нужен переход, отказывает сразу. */
        val NONE: FastbootRejoin = object : FastbootRejoin {
            override val possible = false

            override fun await(): FastbootLane? = null
        }
    }
}
