package io.github.ncorror.nekoflash.core.artifact

/** Нужно ли складывать источник во временный файл, прежде чем отдавать. */
sealed interface ArtifactStagingDecision {
    /** Источник годится как есть. */
    data class NotNeeded(val reason: String) : ArtifactStagingDecision

    /** Стажировать, и вот сколько для этого нужно места. */
    data class Required(
        /**
         * Сколько понадобится места. [ArtifactStaging.UNKNOWN_SIZE] — неизвестно,
         * и проверить заранее нечем: узнать размер можно только прочитав.
         */
        val bytesNeeded: Long,
        val reason: String,
    ) : ArtifactStagingDecision

    /**
     * Стажировать нужно, а негде.
     *
     * Отдельный исход, а не `Required`, который потом упадёт: место проверяется
     * **до** первого байта, потому что оборвавшаяся на середине стажировка
     * оставит после себя половину файла и потраченное время оператора.
     */
    data class NoRoom(val bytesNeeded: Long, val bytesAvailable: Long) : ArtifactStagingDecision
}

/**
 * Решение о стажировке источника.
 *
 * `content://` у SAF не считается обычным seekable-файлом автоматически
 * (`06` §6). Если протокол требует произвольного доступа — а Sideload требует,
 * потому что Recovery вправе попросить один блок не раз, — то источник без него
 * складывается во временный файл приложения с прогрессом, хешем и проверкой
 * места.
 *
 * Решение принимается **до** передачи и отдельно от неё: иначе «не умеет
 * seek» выяснилось бы на первом повторном запросе блока, то есть уже после
 * границы мутации.
 */
object ArtifactStaging {
    /** Размер неизвестен: проверять место заранее нечем. */
    const val UNKNOWN_SIZE: Long = -1L

    /**
     * Нужна ли стажировка.
     *
     * [randomAccessRequired] задаёт протокол, а не источник: тот же файл для
     * `push` годится потоком, а для Sideload — нет.
     *
     * `UNKNOWN` приравнивается к «не умеет», и это не перестраховка: провайдер,
     * который о себе не сказал, ничего не обещал, а проверять догадку на
     * необратимой передаче нечестно.
     */
    fun decide(
        access: ArtifactAccess,
        sizeBytes: Long?,
        randomAccessRequired: Boolean,
        sizeRequiredUpFront: Boolean,
        bytesAvailable: Long,
    ): ArtifactStagingDecision {
        require(sizeBytes == null || sizeBytes >= 0L) { "размер не может быть отрицательным: $sizeBytes" }
        require(bytesAvailable >= 0L) { "свободного места не может быть меньше нуля: $bytesAvailable" }
        return when {
            // Размер, который передача обязана объявить заранее, узнать больше
            // неоткуда: посчитать его можно только прочитав источник целиком, а
            // прочитав — глупо выбрасывать прочитанное.
            sizeBytes == null && sizeRequiredUpFront -> ArtifactStagingDecision.Required(
                UNKNOWN_SIZE,
                "провайдер не сообщает размер, а передача обязана объявить его до первого байта",
            )

            !randomAccessRequired -> ArtifactStagingDecision.NotNeeded(
                "передача читает источник подряд: произвольный доступ не нужен",
            )

            access == ArtifactAccess.SEEKABLE -> ArtifactStagingDecision.NotNeeded(
                "источник умеет читать с произвольного места",
            )

            sizeBytes == null -> ArtifactStagingDecision.Required(
                UNKNOWN_SIZE,
                "провайдер не сообщает ни размера, ни умения читать с произвольного места",
            )

            bytesAvailable < sizeBytes -> ArtifactStagingDecision.NoRoom(sizeBytes, bytesAvailable)

            else -> ArtifactStagingDecision.Required(sizeBytes, reasonFor(access))
        }
    }

    private fun reasonFor(access: ArtifactAccess): String = when (access) {
        ArtifactAccess.NON_SEEKABLE ->
            "источник читается только подряд, а передача просит блоки в своём порядке"

        // Провайдер не сказал о себе ничего, и это не разрешение считать, что
        // он умеет: непроверенная догадка выяснилась бы на первом повторном
        // запросе блока, то есть уже за границей мутации.
        else -> "провайдер не сообщил, умеет ли источник читать с произвольного места"
    }
}
