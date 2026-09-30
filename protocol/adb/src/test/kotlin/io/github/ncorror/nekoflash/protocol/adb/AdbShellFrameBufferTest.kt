package io.github.ncorror.nekoflash.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbShellFrameBufferTest {
    @Test
    fun completeFrameIsReturnedWhole() {
        val buffer = AdbShellFrameBuffer()
        buffer.append(AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "hello".toByteArray()))

        val poll = buffer.poll() as AdbShellFramePoll.Ready

        assertEquals(AdbShellProtocol.ID_STDOUT, poll.frame.id)
        assertArrayEquals("hello".toByteArray(), poll.frame.payload)
        assertEquals(0, buffer.pendingBytes)
    }

    /** Одна рамка может прийти несколькими пакетами WRTE. */
    @Test
    fun frameSplitAcrossArrivalsIsAssembled() {
        val frame = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "split".toByteArray())
        val buffer = AdbShellFrameBuffer()

        buffer.append(frame.copyOfRange(0, 3))
        assertTrue(buffer.poll() is AdbShellFramePoll.Incomplete)
        buffer.append(frame.copyOfRange(3, frame.size))

        assertArrayEquals(
            "split".toByteArray(),
            (buffer.poll() as AdbShellFramePoll.Ready).frame.payload,
        )
    }

    /** Один WRTE может принести несколько рамок и половину следующей. */
    @Test
    fun severalFramesAndATailAreHandledInOrder() {
        val tail = AdbShellProtocol.encode(AdbShellProtocol.ID_STDERR, "tail".toByteArray())
        val buffer = AdbShellFrameBuffer()
        buffer.append(
            AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "one".toByteArray()) +
                AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "two".toByteArray()) +
                tail.copyOfRange(0, 6),
        )

        assertArrayEquals("one".toByteArray(), (buffer.poll() as AdbShellFramePoll.Ready).frame.payload)
        assertArrayEquals("two".toByteArray(), (buffer.poll() as AdbShellFramePoll.Ready).frame.payload)
        assertTrue(buffer.poll() is AdbShellFramePoll.Incomplete)

        buffer.append(tail.copyOfRange(6, tail.size))
        val last = buffer.poll() as AdbShellFramePoll.Ready
        assertEquals(AdbShellProtocol.ID_STDERR, last.frame.id)
        assertArrayEquals("tail".toByteArray(), last.frame.payload)
    }

    /** Незабранный заголовок должен остаться на месте, иначе повтор разберёт мусор. */
    @Test
    fun incompleteFrameLeavesEveryByteInPlace() {
        val frame = AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, "abcdefgh".toByteArray())
        val buffer = AdbShellFrameBuffer()
        buffer.append(frame.copyOfRange(0, 9))

        repeat(3) { assertTrue(buffer.poll() is AdbShellFramePoll.Incomplete) }
        assertEquals(9, buffer.pendingBytes)

        buffer.append(frame.copyOfRange(9, frame.size))
        assertArrayEquals("abcdefgh".toByteArray(), (buffer.poll() as AdbShellFramePoll.Ready).frame.payload)
    }

    @Test
    fun emptyPayloadFrameIsValid() {
        val buffer = AdbShellFrameBuffer()
        buffer.append(AdbShellProtocol.closeStdinFrame())

        val poll = buffer.poll() as AdbShellFramePoll.Ready

        assertEquals(AdbShellProtocol.ID_CLOSE_STDIN, poll.frame.id)
        assertEquals(0, poll.frame.payload.size)
    }

    @Test
    fun impossibleLengthIsReportedAsCorruptRatherThanAllocated() {
        val buffer = AdbShellFrameBuffer()
        buffer.append(byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F))

        assertTrue(buffer.poll() is AdbShellFramePoll.Corrupt)
    }

    @Test
    fun frameLongerThanTheLimitIsCorrupt() {
        val buffer = AdbShellFrameBuffer(maxFrameBytes = 8)
        buffer.append(AdbShellProtocol.encode(AdbShellProtocol.ID_STDOUT, ByteArray(9)))

        assertTrue(buffer.poll() is AdbShellFramePoll.Corrupt)
    }

    @Test
    fun emptyBufferIsIncomplete() {
        assertTrue(AdbShellFrameBuffer().poll() is AdbShellFramePoll.Incomplete)
    }
}
