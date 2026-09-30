package io.github.ncorror.nekoflash.protocol.fastboot

import java.io.InputStream

/** Каким путём шли байты фазы DATA OUT (D077). */
public enum class FastbootDataPath {
    /** Блоками по 16 КиБ через `UsbTransportHandle.send` — [FastbootLane.sendData]. */
    BULK,

    /** Файл дескриптором, URB в ядро — [FastbootLane.sendFile], `:usb:native`. */
    NATIVE_USBFS,
}

/**
 * Выбранный путь и почему: для [FastbootDataPath.BULK] — чем отказала
 * предпроверка нативного, как `ModeSelection` у A2.
 */
public data class FastbootDataRoute(val path: FastbootDataPath, val reason: String) {
    public companion object {
        /** Источник не дал дескриптора — нативный путь не спрашивался. */
        public val NO_DESCRIPTOR: FastbootDataRoute =
            FastbootDataRoute(FastbootDataPath.BULK, "источник не отдаёт дескриптор файла")
    }
}

/** Чем кончилась загрузка полезной нагрузки в буфер устройства. */
public sealed interface FastbootDownloadOutcome {
    /**
     * Устройство приняло объявленный объём и ответило.
     *
     * [reply] — `OKAY` или `FAIL`. Отказ **после** полной передачи это ответ
     * устройства: байты дошли, а принимать их оно отказалось, и путать это с
     * оборванной передачей нельзя.
     */
    public data class Answered(
        val reply: FastbootReply,
        val payload: String,
        val info: List<String>,
        val bytesSent: Long,
        val route: FastbootDataRoute = FastbootDataRoute.NO_DESCRIPTOR,
    ) : FastbootDownloadOutcome

    /**
     * Устройство отказалось **до** фазы данных.
     *
     * Ни одного байта не отправлено, буфер устройства не тронут, рамка цела.
     * Обычный случай — объём больше, чем устройство готово принять.
     */
    public data class Refused(val detail: String) : FastbootDownloadOutcome

    /**
     * Передача начата и не доведена: **что в буфере устройства, неизвестно**.
     *
     * Это `Unknown` (`03` §3), и он не равен отказу. Прошивать из такого буфера
     * нельзя, и повторять ту же передачу по той же полосе тоже: рамка потеряна,
     * а доказать, что прошлые байты не дошли, нечем.
     */
    public data class Unknown(
        val bytesSent: Long,
        val expectedBytes: Long,
        val detail: String,
        val route: FastbootDataRoute = FastbootDataRoute.NO_DESCRIPTOR,
    ) : FastbootDownloadOutcome

    /** Обмен не начался: полоса занята, закрыта или потеряла рамку. */
    public data class NotStarted(val detail: String) : FastbootDownloadOutcome
}

/**
 * Загрузка полезной нагрузки в буфер устройства — фаза DATA OUT.
 *
 * **Команда несёт восемь шестнадцатеричных цифр, а не четыре.** Legacy пишет её
 * как `String.format("%08x", file.length())` в двух местах
 * (`FastbootProtocol.kt`, строки 923 и 1141). Четырьмя цифрами объявляется длина
 * *ответа* устройства — это другое поле другого направления, и спутать их значит
 * объявить не тот размер.
 *
 * **Где здесь граница мутации.** Сам `download:` **ничего не прошивает**: он
 * заполняет буфер загрузки, а раздел меняет `flash:`, которого в этой фазе нет.
 * Но буфер — тоже состояние устройства, и оборванная передача оставляет его
 * неизвестным. Поэтому:
 *
 * - `Refused` до данных означает, что буфер **не тронут**: это единственный
 *   исход, о котором можно сказать «ничего не изменилось»;
 * - `Unknown` означает, что в буфере неизвестно что, и прошивать из него нельзя;
 * - автоматического повтора нет ни в одном случае. Повторить те же байты после
 *   неоднозначной записи запрещено, потому что доказать, что предыдущая попытка
 *   не дошла, нечем.
 *
 * **Ответ не `DATA` при живом обмене ломает рамку.** Legacy на таком кадре
 * помечает сессию `BROKEN` («Expected DATA, got …»), и мы поступаем так же:
 * устройство ответило не то, о чём мы договаривались, и что оно делает дальше —
 * неизвестно.
 *
 * **`max-download-size` здесь не проверяется, и это намеренно.** Устройство
 * само знает свой предел и отвечает `FAIL`, если объём ему велик. Превращать его
 * диагностическое поле в нашу проверку значило бы завести ту самую систему
 * авторизации, которую `03` §2 прямо запрещает: отказ принадлежит устройству.
 *
 * **Путь байтов выбирается до `download:` и не меняется посреди данных**
 * (D077, A2 `resolveMode` в режиме `AUTO`). Дан дескриптор файла и транспорт
 * прошёл предпроверку — байты идут нативным путём; нет — тем же потоком, что
 * и без дескриптора. Отказ предпроверки — не ошибка, а причина в исходе.
 */
public class FastbootDownload(private val lane: FastbootLane) {
    /**
     * Просит устройство принять [sizeBytes] байт и передаёт их из [source].
     *
     * Источник читается ровно на объявленный объём и не закрывается здесь:
     * закрывает тот, кто открыл.
     *
     * [payloadFd] — дескриптор обычного файла с теми же байтами, что в
     * [source]; с ним байты могут пойти нативным путём. Дескриптор тоже
     * закрывает вызывающий.
     */
    public fun send(
        source: InputStream,
        sizeBytes: Long,
        inactivityMillis: Long = FastbootLane.DEFAULT_INACTIVITY_MS,
        payloadFd: Int? = null,
    ): FastbootDownloadOutcome {
        require(sizeBytes >= 0L) { "объём не может быть отрицательным" }
        val route = route(payloadFd, sizeBytes)
        return when (val opened = lane.run(command(sizeBytes), inactivityMillis)) {
            is FastbootExchange.DataPhase ->
                transfer(Payload(source, payloadFd, sizeBytes, route), opened, inactivityMillis)

            // Терминальный ответ вместо `DATA`: устройство отказалось принимать.
            // Ни одного байта не отправлено — единственный исход, про который
            // можно честно сказать, что ничего не изменилось.
            is FastbootExchange.Completed -> FastbootDownloadOutcome.Refused(
                "${opened.reply.name}: ${opened.payload.ifBlank { "без объяснения" }}",
            )

            is FastbootExchange.TimedOut -> FastbootDownloadOutcome.Unknown(
                bytesSent = 0L,
                expectedBytes = sizeBytes,
                detail = "ответа на download не было ${opened.waitedMillis} мс",
                route = route,
            )

            is FastbootExchange.NotReady -> FastbootDownloadOutcome.NotStarted("полоса занята: ${opened.state}")
            is FastbootExchange.NotSent -> FastbootDownloadOutcome.NotStarted(opened.reason)
        }
    }

    /** Предпроверка нативного пути — до `download:`, пока устройство ещё не ждёт данных. */
    private fun route(payloadFd: Int?, sizeBytes: Long): FastbootDataRoute {
        val refusal = payloadFd?.let { lane.fileSendUnavailable(it, sizeBytes) }
        return when {
            payloadFd == null -> FastbootDataRoute.NO_DESCRIPTOR
            refusal != null -> FastbootDataRoute(FastbootDataPath.BULK, refusal)
            else -> FastbootDataRoute(FastbootDataPath.NATIVE_USBFS, "предпроверка пройдена")
        }
    }

    private fun transfer(
        payload: Payload,
        opened: FastbootExchange.DataPhase,
        inactivityMillis: Long,
    ): FastbootDownloadOutcome {
        // Устройство назвало свой объём — сверяем. Расхождение означает, что мы
        // договорились о разном, и передавать в такой обмен нельзя.
        val declared = opened.declaredSize
        val sizeBytes = payload.sizeBytes
        return if (declared != null && declared != sizeBytes) {
            lane.stall()
            FastbootDownloadOutcome.Unknown(
                bytesSent = 0L,
                expectedBytes = sizeBytes,
                detail = "устройство ждёт $declared байт, а объявлено было $sizeBytes",
                route = payload.route,
            )
        } else {
            val data = if (payload.route.path == FastbootDataPath.NATIVE_USBFS && payload.fd != null) {
                lane.sendFile(payload.fd, sizeBytes, inactivityMillis)
            } else {
                lane.sendData(payload.stream, sizeBytes, inactivityMillis)
            }
            outcomeOf(data, payload.route)
        }
    }

    private fun outcomeOf(data: FastbootDataOutcome, route: FastbootDataRoute): FastbootDownloadOutcome = when (data) {
        is FastbootDataOutcome.Completed ->
            FastbootDownloadOutcome.Answered(data.reply, data.payload, data.info, data.bytesSent, route)

        is FastbootDataOutcome.Interrupted ->
            FastbootDownloadOutcome.Unknown(data.bytesSent, data.expectedBytes, data.detail, route)

        is FastbootDataOutcome.NotReady ->
            FastbootDownloadOutcome.NotStarted("фаза данных не открыта: ${data.state}")
    }

    /** Байты одной загрузки: поток, дескриптор того же файла и выбранный до `download:` путь. */
    private class Payload(
        val stream: InputStream,
        val fd: Int?,
        val sizeBytes: Long,
        val route: FastbootDataRoute,
    )

    /** `download:` с восьмизначным шестнадцатеричным объёмом — как в Legacy. */
    internal fun command(sizeBytes: Long): String = PREFIX + "%08x".format(sizeBytes)

    private companion object {
        const val PREFIX = "download:"
    }
}
