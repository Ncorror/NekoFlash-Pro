package io.github.ncorror.nekoflash.protocol.adb

/** Чем кончился запрос обратного проброса. */
public sealed interface AdbReverseOutcome {
    /** Устройство приняло запрос. [body] — то, что оно сказало сверх этого. */
    public data class Accepted(val service: String, val body: String) : AdbReverseOutcome

    /** Устройство отказало или ответило непонятным. */
    public data class Failed(val service: String, val detail: String) : AdbReverseOutcome

    /** Запрос не дошёл: сервиса нет, транспорт умер, кадр потерян. */
    public data class Unreachable(val service: String, val reason: AdbServiceFailure, val detail: String) :
        AdbReverseOutcome
}

/**
 * Запросы обратного проброса к устройству.
 *
 * Сам по себе `reverse` устройству — это обычный сервис ADB: спросили, оно
 * ответило, поток закрылся. Отличие от `forward` целиком в том, что **слушать
 * начинает устройство**, а соединения приходят к нам входящими потоками; за них
 * отвечает [AdbInboundStreams], а не этот класс
 * (`docs/adr/0005_LOCAL_SOCKET_FORWARDING_RU.md` §1).
 *
 * **Ответ берётся сырым, и это не мелочь.** [AdbServiceOutcome.Completed.text]
 * срезает концевые переводы строк, а тело ответа их содержит: непустой список
 * пришёл как `0019` и двадцать пять символов, из которых двадцать пятый —
 * перевод строки. После обрезки длина перестаёт сходиться, и верный ответ
 * устройства пришлось бы объявить непонятым. Наблюдено на устройстве,
 * записано в `07` §6.62.
 */
public class AdbReverse(private val services: AdbServiceCall) {
    /**
     * Просит устройство слушать [onDevice] и приводить соединения к [onHost].
     *
     * Ни один из адресов не проверяется: какие бывают, знает устройство, и его
     * отказ — это ответ, а не наша ошибка (`01` §3).
     */
    public fun forward(onDevice: String, onHost: String): AdbReverseOutcome =
        request(AdbReverseService.forward(onDevice, onHost))

    /** Спрашивает, что устройство слушает сейчас. */
    public fun list(): AdbReverseOutcome = request(AdbReverseService.LIST)

    /** Снимает все обратные пробросы разом. */
    public fun killAll(): AdbReverseOutcome = request(AdbReverseService.KILL_ALL)

    /**
     * Вызывает произвольный сервис семейства `reverse:` и разбирает ответ.
     *
     * Открыт наружу намеренно: семейство не исчерпывается тремя запросами,
     * которые мы наблюдали, и запирать его списком значило бы решать за
     * оператора, чего у устройства просить (`01` §3).
     */
    public fun request(service: String): AdbReverseOutcome =
        when (val outcome = services.run(service)) {
            is AdbServiceOutcome.Completed -> read(service, outcome.output)

            is AdbServiceOutcome.Failed ->
                AdbReverseOutcome.Unreachable(service, outcome.reason, outcome.detail)
        }

    private fun read(service: String, output: ByteArray): AdbReverseOutcome =
        when (val reply = AdbReverseProtocol.parse(output.toString(Charsets.UTF_8))) {
            is AdbReverseReply.Accepted -> AdbReverseOutcome.Accepted(service, reply.body)
            is AdbReverseReply.Refused -> AdbReverseOutcome.Failed(service, reply.detail)

            // Непонятое не выдаётся ни за успех, ни за отказ: текст отдаётся
            // целиком, чтобы по нему было видно, что наблюдение было неполным.
            is AdbReverseReply.Unreadable ->
                AdbReverseOutcome.Failed(service, "unreadable reply: ${reply.raw}")
        }
}
