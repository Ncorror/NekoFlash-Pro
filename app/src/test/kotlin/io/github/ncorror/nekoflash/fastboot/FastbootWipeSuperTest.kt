package io.github.ncorror.nekoflash.fastboot

import com.ncorror.nekoflash.pro.engine.CURRENT_SLOT
import io.github.ncorror.nekoflash.core.artifact.ArtifactAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactIdentity
import io.github.ncorror.nekoflash.core.artifact.ArtifactRandomAccess
import io.github.ncorror.nekoflash.core.artifact.ArtifactSource
import io.github.ncorror.nekoflash.core.artifact.ArtifactStamp
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootLane
import io.github.ncorror.nekoflash.protocol.fastboot.FastbootSparse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `fastboot wipe-super` — как `wipe_super` настольного клиента. */
class FastbootWipeSuperTest {
    private val emptySuper = object : ArtifactSource {
        private val bytes = FastbootFlashAllTest.superEmpty()
        override val identity = ArtifactIdentity("super_empty.img", bytes.size.toLong(), ArtifactAccess.SEEKABLE)
        override val openedStamp = ArtifactStamp(bytes.size.toLong())
        override fun stamp() = openedStamp
        override fun open() = bytes.inputStream()
        override fun randomAccess(): ArtifactRandomAccess? = null
    }

    @Test
    fun theEmptySuperIsFlashedAsASparseImage() {
        val device = ScriptedBootloader(mapOf("max-download-size" to "0x10000000"))

        val planned = wipeSuper(FastbootLane(device), emptySuper, CURRENT_SLOT, null) as FastbootConsoleState.Planned

        assertEquals(null, planned.stoppedAt)
        assertEquals("flash:super", device.sent.last())
        val staged = ByteBuffer.wrap(device.downloads.single()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(FastbootSparse.MAGIC, staged.getInt(0))
        assertTrue("oem allow-flash-super" !in device.sent)
    }

    @Test
    fun anUnreadableImageStopsBeforeAnyCommand() {
        val device = ScriptedBootloader(emptyMap())
        val garbage = object : ArtifactSource by emptySuper {
            override fun open() = ByteArray(5000).inputStream()
        }

        val planned = wipeSuper(FastbootLane(device), garbage, CURRENT_SLOT, null) as FastbootConsoleState.Planned

        assertTrue(planned.detail, planned.detail.startsWith("Could not parse image: super_empty.img"))
        assertTrue(device.sent.isEmpty())
    }
}
