package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.ByteArrayInputStream

/** Xiaomi unlock uses the same Fastboot lane as every other device operation. */
public class FastbootXiaomiUnlock(private val lane: FastbootLane) {
    /** This value must never be sent to diagnostics or a console state. */
    public fun readToken(inactivityMillis: Long = 30_000L): String? {
        when (val direct = FastbootGetVar(lane).read("token", inactivityMillis)) {
            is FastbootVariable.Present -> tokenPart(direct.value)?.let { return it }
            is FastbootVariable.Unavailable -> return null // A stalled lane cannot take a fallback command.
            is FastbootVariable.Unsupported -> Unit
        }
        return when (val answer = lane.run("oem get_token", inactivityMillis)) {
            is FastbootExchange.Completed -> if (answer.reply == FastbootReply.OKAY) {
                (answer.info + answer.payload).mapNotNull(::tokenPart)
                    .joinToString("").takeIf { it.isNotBlank() }
            } else null
            is FastbootExchange.DataPhase -> { lane.stall(); null }
            else -> null
        }
    }

    /** Download must be acknowledged before the only mutating command is sent. */
    public fun unlock(encryptData: ByteArray, beforeCommand: () -> Unit = {}): XiaomiUnlockOutcome {
        if (encryptData.isEmpty() || encryptData.size > MAX_DATA_BYTES) {
            return XiaomiUnlockOutcome.NotStarted("некорректный объём данных разблокировки")
        }
        val download = FastbootDownload(lane).send(ByteArrayInputStream(encryptData), encryptData.size.toLong(), 30_000L)
        when (download) {
            is FastbootDownloadOutcome.Answered -> if (download.reply != FastbootReply.OKAY) {
                return XiaomiUnlockOutcome.Refused("загрузчик отклонил данные")
            }
            is FastbootDownloadOutcome.Refused -> return XiaomiUnlockOutcome.Refused("загрузчик отказался принять данные")
            is FastbootDownloadOutcome.Unknown -> return XiaomiUnlockOutcome.Unknown("передача данных оборвалась")
            is FastbootDownloadOutcome.NotStarted -> return XiaomiUnlockOutcome.NotStarted("передача данных не началась")
        }
        beforeCommand()
        return when (val reply = lane.run("oem unlock", 30_000L)) {
            is FastbootExchange.Completed -> if (reply.reply == FastbootReply.OKAY) {
                XiaomiUnlockOutcome.Accepted
            } else XiaomiUnlockOutcome.Refused("загрузчик отказал в разблокировке")
            is FastbootExchange.TimedOut -> XiaomiUnlockOutcome.Unknown("ответ на oem unlock не получен")
            is FastbootExchange.NotSent -> XiaomiUnlockOutcome.NotStarted("oem unlock не отправлена")
            is FastbootExchange.NotReady -> XiaomiUnlockOutcome.NotStarted("Fastboot недоступен")
            is FastbootExchange.DataPhase -> { lane.stall(); XiaomiUnlockOutcome.Unknown("неожиданная фаза данных") }
        }
    }

    private fun tokenPart(raw: String): String? {
        val clean = raw.trim().removePrefix("INFO").trim()
        val value = if (clean.startsWith("token:", ignoreCase = true)) clean.substringAfter(':') else clean
        return value.filterNot(Char::isWhitespace).takeIf { it.length >= 8 && it.any(Char::isLetterOrDigit) }
    }

    private companion object { const val MAX_DATA_BYTES = 8 * 1024 * 1024 }
}

/** Accepted still needs a fresh `getvar:unlocked` after reconnect. */
public sealed interface XiaomiUnlockOutcome {
    public data object Accepted : XiaomiUnlockOutcome
    public data class Refused(val detail: String) : XiaomiUnlockOutcome
    public data class Unknown(val detail: String) : XiaomiUnlockOutcome
    public data class NotStarted(val detail: String) : XiaomiUnlockOutcome
}
