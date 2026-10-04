package io.github.ncorror.nekoflash

import io.github.ncorror.nekoflash.core.model.SessionGeneration
import io.github.ncorror.nekoflash.core.model.TargetId
import io.github.ncorror.nekoflash.usb.api.UsbSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One operator-selected target and the USB generation currently proven to
 * belong to it.
 *
 * A generation is deliberately optional: after detach a serial-backed target
 * may remain selected while the app waits for the same target to enumerate
 * again. Attachment-only identity cannot make that promise, so it never jumps
 * to another session automatically.
 */
internal data class TargetSelection(
    val targetId: TargetId,
    val generation: SessionGeneration?,
    val survivesReattachment: Boolean,
)

/** Owns target selection; protocol controllers own transports, never selection. */
internal class TargetSelectionController {
    private val mutableState = MutableStateFlow<TargetSelection?>(null)

    val state: StateFlow<TargetSelection?> = mutableState.asStateFlow()

    /** Explicit operator choice always wins over any previous selection. */
    fun select(session: UsbSession) {
        mutableState.value = session.asSelection()
    }

    /**
     * Reconciles selection with live USB sessions without inventing identity.
     *
     * - one unambiguous target may be selected on startup;
     * - two or more targets require an explicit choice;
     * - the same generation may refine attachment identity into serial identity;
     * - only serial-backed identity follows a new generation automatically.
     */
    fun reconcile(sessions: List<UsbSession>) {
        val live = sessions.filterNot(UsbSession::closed)
        val selected = mutableState.value
        mutableState.value = when {
            selected == null -> live.singleOrNull()?.asSelection()
            selected.generation != null -> reconcileAttached(selected, live)
            selected.survivesReattachment -> reattach(selected, live)
            else -> selected
        }
    }

    /** The selected live session, or null while the selected target is detached/unresolved. */
    fun resolve(sessions: List<UsbSession>): UsbSession? {
        val generation = mutableState.value?.generation ?: return null
        return sessions.firstOrNull { session -> !session.closed && session.generation == generation }
    }

    private fun reconcileAttached(selected: TargetSelection, live: List<UsbSession>): TargetSelection {
        val sameGeneration = live.firstOrNull { it.generation == selected.generation }
        if (sameGeneration != null) return sameGeneration.asSelection()
        return selected.copy(generation = null).let { detached ->
            if (detached.survivesReattachment) reattach(detached, live) else detached
        }
    }

    private fun reattach(selected: TargetSelection, live: List<UsbSession>): TargetSelection =
        live.singleOrNull { session -> session.targetId == selected.targetId }?.asSelection() ?: selected

    private fun UsbSession.asSelection(): TargetSelection = TargetSelection(
        targetId = targetId,
        generation = generation,
        survivesReattachment = identity.survivesReattachment,
    )
}
