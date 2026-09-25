package com.ncorror.nekoflash.pro.fastboot

import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.transport.FastbootPacketTransport
import java.io.EOFException
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FastbootPlanExecutorTest {
    private class FakeTransport(vararg replies: String) : FastbootPacketTransport {
        val incoming = ArrayDeque(replies.map { it.toByteArray(Charsets.US_ASCII) })
        val written = mutableListOf<String>()
        var closed = false

        override fun writePacket(packet: ByteArray) {
            check(!closed)
            written += packet.toString(Charsets.US_ASCII)
        }

        override fun readPacket(maxBytes: Int): ByteArray {
            check(!closed)
            if (incoming.isEmpty()) throw EOFException("disconnected")
            return incoming.removeFirst().also { check(it.size <= maxBytes) }
        }

        override fun close() { closed = true }
    }

    private val compiler = TerminalPlanCompiler()

    @Test
    fun previewAndExecutionUseTheSameProductPlan() {
        val ready = assertIs<com.ncorror.nekoflash.pro.engine.TerminalPlanResult.Ready>(
            compiler.compile("fastboot getvar product")
        )
        val fake = FakeTransport("INFOchecking", "OKAYvayu")
        val messages = mutableListOf<FastbootWireMessage>()
        val result = FastbootPlanExecutor { fake }.execute(ready) { messages += it }
        assertEquals(listOf("Fastboot: read getvar:product"), ready.plan.preview())
        assertEquals(FastbootPlanExecution.Reply(FastbootWireResult.Okay("vayu")), result)
        assertEquals(listOf("getvar:product"), fake.written)
        assertEquals(listOf<FastbootWireMessage>(FastbootWireMessage.Info("checking")), messages)
        assertTrue(fake.closed)
    }

    @Test
    fun unsupportedOrUnsafeCommandsNeverOpenTheTransport() {
        var opens = 0
        val executor = FastbootPlanExecutor {
            opens++
            FakeTransport("OKAYunexpected")
        }
        for (line in listOf(
            "fastboot getvar all", "fastboot raw getvar:product",
            "fastboot --force getvar product", "fastboot -w", "fastboot flash boot boot.img",
            "adb reboot bootloader", "fastboot getvar product extra",
        )) {
            assertIs<FastbootPlanExecution.Refused>(executor.execute(compiler.compile(line)), line)
        }
        assertEquals(0, opens)
    }

    @Test
    fun deviceFailureAndUnexpectedDataKeepTheirProtocolMeaningAndClose() {
        val command = compiler.compile("fastboot getvar version")
        val rejected = FakeTransport("FAILGetVar Variable Not found")
        assertEquals(FastbootPlanExecution.Reply(FastbootWireResult.Fail("GetVar Variable Not found")),
            FastbootPlanExecutor { rejected }.execute(command))
        assertTrue(rejected.closed)

        val data = FakeTransport("DATA00001000")
        assertEquals(FastbootPlanExecution.Reply(FastbootWireResult.DataPhaseRequired(4096L)),
            FastbootPlanExecutor { data }.execute(command))
        assertTrue(data.closed)

        val disconnected = FakeTransport()
        assertFailsWith<EOFException> { FastbootPlanExecutor { disconnected }.execute(command) }
        assertTrue(disconnected.closed)
    }
}
