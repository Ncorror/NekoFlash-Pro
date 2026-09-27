package io.github.ncorror.nekoflash.protocol.adb

/**
 * В каком состоянии отвечает peer.
 *
 * Определяется по префиксу баннера, и это единственный источник: доверять
 * догадкам по составу сервисов нельзя, потому что в Recovery и Sideload набор
 * доступного различается сильнее, чем кажется.
 */
public enum class AdbPeerMode {
    /** Обычная система с работающим `adbd`. */
    DEVICE,

    /** Recovery: набор сервисов урезан, записи почти нет. */
    RECOVERY,

    /** Sideload: peer ждёт поток пакета и почти ничего больше не умеет. */
    SIDELOAD,

    /**
     * Баннер не начинается ни с одного известного префикса.
     *
     * Отдельное значение, а не подстановка `DEVICE`: считать незнакомый peer
     * обычным устройством означало бы разрешить операции, которых он может не
     * пережить.
     */
    UNKNOWN,
}

/** Что peer сообщил о себе в `CNXN`. */
public data class AdbConnectionBanner(
    val banner: String,
    val peerMode: AdbPeerMode,
    val features: Set<String>,
) {
    public companion object {
        /**
         * Разбирает payload `CNXN`.
         *
         * Формат перенесён из Legacy `handleConnectionBanner` и A2
         * `readConnectionBanner`, где он записан одинаково: строка вида
         * `device::ro.product.name=...;features=shell_v2,cmd`, завершённая
         * нулём. Части разделены `;`, список возможностей внутри `features=` —
         * запятыми.
         */
        public fun parse(payload: ByteArray): AdbConnectionBanner {
            val banner = payload.toString(Charsets.UTF_8).trimEnd('\u0000')
            return AdbConnectionBanner(
                banner = banner,
                peerMode = peerModeOf(banner),
                features = featuresOf(banner),
            )
        }

        private fun peerModeOf(banner: String): AdbPeerMode = when {
            banner.startsWith("sideload::", ignoreCase = true) -> AdbPeerMode.SIDELOAD
            banner.startsWith("recovery::", ignoreCase = true) -> AdbPeerMode.RECOVERY
            banner.startsWith("device::", ignoreCase = true) -> AdbPeerMode.DEVICE
            else -> AdbPeerMode.UNKNOWN
        }

        /**
         * Возможности ищутся по вхождению `features=`, а не только среди
         * частей, разделённых `;`.
         *
         * Оба архива берут именно отдельную часть, и для баннера обычной
         * системы это одно и то же: там перед `features=` всегда есть свойства
         * и точка с запятой. Но peer вправе прислать `recovery::features=cmd`
         * без единого свойства, и тогда прежний разбор сообщил бы, что
         * возможностей нет вовсе, а команда ушла бы устаревшей веткой.
         * Расширение ничего не меняет для проверенного на железе баннера и
         * закрывает форму, которую мы просто не встречали.
         */
        private fun featuresOf(banner: String): Set<String> {
            val start = banner.indexOf(FEATURES_PREFIX)
            if (start < 0) return emptySet()
            return banner
                .substring(start + FEATURES_PREFIX.length)
                .substringBefore(';')
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        }

        private const val FEATURES_PREFIX = "features="
    }
}
