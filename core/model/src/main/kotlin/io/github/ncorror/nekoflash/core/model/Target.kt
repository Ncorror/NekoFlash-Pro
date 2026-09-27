package io.github.ncorror.nekoflash.core.model

@JvmInline
value class TargetId(val value: String) {
    init {
        require(value.isNotBlank()) { "TargetId must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class SessionGeneration(val value: Long) {
    init {
        require(value > 0L) { "SessionGeneration must be positive" }
    }

    override fun toString(): String = value.toString()
}

enum class TargetMode {
    ADB,
    RECOVERY,
    SIDELOAD,
    BOOTLOADER_FASTBOOT,
    FASTBOOTD,
    UNKNOWN,
}

data class TargetSnapshot(
    val id: TargetId,
    val mode: TargetMode,
    val sessionGeneration: SessionGeneration?,
    val displayName: String? = null,
)
