package com.ncorror.nekoflash.pro.adb

import com.ncorror.nekoflash.pro.engine.TerminalParser
import com.ncorror.nekoflash.pro.engine.TerminalPlanCompiler
import com.ncorror.nekoflash.pro.transport.AdbByteTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdbPlanExecutorTest {
    private class FakeTransport(vararg packets: ByteArray) : AdbByteTransport {
        val incoming = ArrayDeque(packets.toList())
        val sent = mutableListOf<ByteArray>()
        var closed = false
        override fun write(bytes: ByteArray) { check(!closed); sent += bytes.copyOf() }
        override fun readExact(length: Int): ByteArray {
            if (length == 0) return byteArrayOf()
            return incoming.removeFirst().also { assertEquals(length, it.size) }
        }
        override fun close() { closed = true }
    }

    private fun packet(command: Int, arg0: Int, arg1: Int, payload: String = ""): List<ByteArray> {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(bytes.size)
            .putInt(bytes.sumOf { it.toInt() and 0xff }).putInt(command.inv()).array()
        return if (bytes.isEmpty()) listOf(header) else listOf(header, bytes)
    }

    @Test
    fun planPreviewAndWireExecutionAgreeOnOneReadOnlyService() {
        val compiled = TerminalPlanCompiler().compile(TerminalParser.Tool.ADB, "shell getprop ro.product.device")
        val plan = assertIs<com.ncorror.nekoflash.pro.engine.TerminalPlanResult.Ready>(compiled).plan
        val fake = FakeTransport(*(packet(AdbWire.CNXN, AdbWire.VERSION, 4096) +
            packet(AdbWire.OKAY, 2, 1) + packet(AdbWire.WRTE, 2, 1, "vayu\n") +
            packet(AdbWire.CLSE, 2, 1)).toTypedArray())
        val executor = AdbPlanExecutor(
            keyFactory = { object : AdbHostKey {
                override fun signChallenge(token: ByteArray) = ByteArray(256)
                override fun publicKeyText() = byteArrayOf(1)
            } },
            openTransport = { fake },
        )
        val reply = assertIs<AdbPlanExecution.Reply>(executor.execute(compiled) {})
        assertEquals("ADB: read ro.product.device", plan.preview().single())
        assertEquals("vayu", assertIs<AdbProductResult.Value>(reply.result).productCode)
        assertEquals("shell:getprop ro.product.device\u0000", fake.sent[3].toString(Charsets.US_ASCII))
        assertTrue(fake.closed)
    }

    @Test
    fun unsupportedShellDoesNotCreateAKeyOrOpenUsb() {
        var keys = 0
        var opens = 0
        val executor = AdbPlanExecutor(
            keyFactory = { keys++; error("must not create key") },
            openTransport = { opens++; error("must not claim USB") },
        )
        val compiler = TerminalPlanCompiler()
        for (line in listOf("shell reboot", "shell getprop ro.serialno", "raw shell:getprop ro.product.device")) {
            assertIs<AdbPlanExecution.Refused>(
                executor.execute(compiler.compile(TerminalParser.Tool.ADB, line)) {}, line
            )
        }
        assertEquals(0, keys)
        assertEquals(0, opens)
    }
}
