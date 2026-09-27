package io.github.ncorror.nekoflash.core.operation

import io.github.ncorror.nekoflash.core.model.SessionGeneration

/**
 * Что можно честно сказать про операцию, пережившую смерть процесса.
 *
 * Обратите внимание, чего в списке **нет**: продолжения. Возобновление
 * допускается только как явно определённый протоколом recovery workflow
 * (`06` §3), и ни у одной из сегодняшних операций такого workflow нет. Сказать
 * «продолжаем» значило бы пообещать транзакцию, которой больше не существует:
 * транспорт отпущен, поколение сессии другое, а устройство про нас забыло.
 */
sealed interface OperationRestoration {
    /** Операция закончилась до смерти процесса, и исход записан. */
    data class Finished(val outcome: OperationOutcome, val detail: String) : OperationRestoration

    /**
     * Операция оборвалась **до** границы мутации: устройство не тронуто.
     *
     * Это единственный случай, где можно спокойно сказать, что ничего не
     * произошло, — и сказать так можно потому, что граница отмечается **до**
     * того, как байты попадут на провод, а не после.
     */
    data class Abandoned(val detail: String) : OperationRestoration

    /**
     * Операция оборвалась **после** границы мутации.
     *
     * Состояние устройства неизвестно, **каким бы ни выглядел процент**:
     * устройство начинает меняться, как только у него появились данные.
     * [verify] говорит, чем это проверить, — потому что «неизвестно» без
     * следующего шага оставляет оператора там же, где он был.
     */
    data class NeedsVerification(val detail: String, val verify: String) : OperationRestoration
}

/**
 * Чтение записи операции после перезапуска процесса.
 *
 * Правила `06` §3 целиком, и все четыре легко нарушить незаметно:
 *
 * 1. запись восстанавливается;
 * 2. определяется текущее поколение сессии;
 * 3. **не заявляется, что старая транзакция продолжилась**;
 * 4. если операция была после границы мутации и финальное состояние
 *    неизвестно — `Unknown / Needs verification`.
 */
object OperationRecovery {
    /**
     * Что сказать про [record] при текущем поколении [currentGeneration].
     *
     * `null` в поколении означает, что устройства сейчас нет вовсе. На вывод
     * это влияет меньше, чем кажется: даже **то же самое** устройство в том же
     * поколении не возвращает нам оборванную транзакцию, потому что транспорт
     * был отпущен вместе с процессом. Поколение поэтому уточняет **формулировку**,
     * а не решает, можно ли продолжать.
     */
    fun restore(
        record: OperationRecord,
        currentGeneration: SessionGeneration?,
    ): OperationRestoration = when {
        record.outcome != null -> OperationRestoration.Finished(
            outcome = record.outcome,
            detail = "операция закончилась до перезапуска: ${record.intent.summary}",
        )

        record.mutationBoundary is MutationBoundary.Crossed ->
            needsVerification(record, record.mutationBoundary, currentGeneration)

        else -> OperationRestoration.Abandoned(
            "операция прервана перезапуском до границы мутации, устройство не тронуто: " +
                "${record.intent.summary} (${record.state})",
        )
    }

    private fun needsVerification(
        record: OperationRecord,
        boundary: MutationBoundary.Crossed,
        currentGeneration: SessionGeneration?,
    ): OperationRestoration.NeedsVerification {
        val covered = record.progress?.percent
        // Процент называется, но тут же оговаривается: он **не** уменьшает
        // неизвестность. Устройство начинает меняться с первых полученных
        // данных, и «передано мало» не означает «ничего не изменилось».
        val progress = if (covered == null) {
            "сколько успело уйти — неизвестно"
        } else {
            "успело уйти $covered%, и это ничего не говорит о состоянии устройства"
        }
        return OperationRestoration.NeedsVerification(
            detail = "операция прервана перезапуском после границы мутации " +
                "(${boundary.detail}); $progress",
            verify = verificationFor(record.intent.kind, currentGeneration),
        )
    }

    /**
     * Чем проверить, что на устройстве.
     *
     * Совет зависит от рода операции, потому что общего ответа нет: у Sideload
     * это слово Recovery, у записи файла — чтение того же пути, у Fastboot —
     * состояние раздела.
     */
    private fun verificationFor(kind: OperationKind, currentGeneration: SessionGeneration?): String {
        val reconnect = if (currentGeneration == null) {
            "подключите устройство и "
        } else {
            ""
        }
        return reconnect + when (kind) {
            OperationKind.ADB_SIDELOAD ->
                "прочитайте вердикт Recovery: сказать, установилось ли, может только оно"

            OperationKind.ADB_PUSH ->
                "проверьте файл назначения на устройстве: из обрыва не следует, что он цел"

            OperationKind.ADB_PULL, OperationKind.FASTBOOT_FETCH ->
                "проверьте файл назначения на хосте: он мог остаться недописанным"

            OperationKind.FASTBOOT_DOWNLOAD ->
                "буфер загрузки устройства в неизвестном состоянии: перезалейте его целиком перед записью"

            OperationKind.FASTBOOT_MUTATION, OperationKind.FASTBOOT_PLAN ->
                "проверьте состояние раздела и активный слот: команда могла дойти до устройства"
        }
    }
}
