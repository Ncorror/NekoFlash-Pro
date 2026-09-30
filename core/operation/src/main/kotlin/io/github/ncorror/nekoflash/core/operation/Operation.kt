package io.github.ncorror.nekoflash.core.operation

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import java.time.Instant

@JvmInline
value class OperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "OperationId must not be blank" }
    }

    override fun toString(): String = value
}

data class OperationContext(
    val id: OperationId,
    val targetId: TargetId,
    val sessionGeneration: SessionGeneration,
)

sealed interface MutationBoundary {
    data object NotCrossed : MutationBoundary

    data class Crossed(
        val at: Instant,
        val detail: String,
    ) : MutationBoundary {
        init {
            require(detail.isNotBlank()) { "Mutation detail must not be blank" }
        }
    }
}

enum class OperationOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}
