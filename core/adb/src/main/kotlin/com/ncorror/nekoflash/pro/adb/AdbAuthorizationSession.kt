package com.ncorror.nekoflash.pro.adb

import com.ncorror.nekoflash.pro.transport.AdbByteTransport

/** Signing and public-key encoding are provided by the platform; private keys never enter core. */
interface AdbHostKey {
    fun signChallenge(token: ByteArray): ByteArray
    fun publicKeyText(): ByteArray
}

/** Starts after Android grants USB access, or when the user retries the ADB connection. */
class AdbAuthorizationSession(private val transport: AdbByteTransport, private val key: AdbHostKey) {
    fun connect(onAwaitingApproval: () -> Unit): AdbHandshakeResult =
        transport.use { handshake(onAwaitingApproval) }

    /** The caller owns the transport when a service must follow CNXN in the same session. */
    internal fun handshake(onAwaitingApproval: () -> Unit): AdbHandshakeResult {
        val wire = AdbWire(transport)
        wire.sendHello()
        var signed = false
        var publicKeySent = false
        while (true) {
            val reply = wire.receive(awaitApproval = publicKeySent)
            when (reply.command) {
                AdbWire.CNXN -> return wire.connected(reply).copy(authentication = when {
                    publicKeySent -> AdbAuthenticationPath.PUBLIC_KEY
                    signed -> AdbAuthenticationPath.SIGNATURE
                    else -> AdbAuthenticationPath.NO_CHALLENGE
                })
                AdbWire.STLS -> return wire.tls(reply)
                AdbWire.AUTH -> {
                    val token = wire.token(reply)
                    when {
                        !signed -> {
                            signed = true
                            val signature = key.signChallenge(token)
                            if (signature.size != 256) throw AdbProtocolException("Unexpected ADB signature length")
                            wire.send(AdbWire.AUTH, AdbWire.AUTH_SIGNATURE, 0, signature)
                        }
                        !publicKeySent -> {
                            publicKeySent = true
                            val publicKey = key.publicKeyText()
                            if (publicKey.isEmpty() || publicKey.size >= AdbWire.MAX_DATA ||
                                publicKey.any { it == 0.toByte() }) {
                                throw AdbProtocolException("Invalid ADB public key")
                            }
                            wire.send(AdbWire.AUTH, AdbWire.AUTH_PUBLIC_KEY, 0,
                                publicKey + byteArrayOf(0))
                            onAwaitingApproval()
                        }
                        else -> throw AdbProtocolException("ADB authorization not accepted")
                    }
                }
                else -> throw AdbProtocolException("Unexpected ADB reply during authorization")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw AssertionError("ADB authorization loop ended")
    }
}
