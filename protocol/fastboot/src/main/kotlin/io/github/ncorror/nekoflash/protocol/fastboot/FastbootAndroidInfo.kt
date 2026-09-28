package io.github.ncorror.nekoflash.protocol.fastboot

/**
 * Строка `android-info.txt`: `require ИМЯ=A|B`, `reject ИМЯ=A`,
 * `require-for-product:ПРОДУКТ ИМЯ=A|B`.
 *
 * [product] непустой — требование только для этого продукта; [invert] — `reject`.
 */
public data class FastbootRequirement(
    val name: String,
    val product: String,
    val invert: Boolean,
    val options: List<String>,
)

/** Итог одного требования — словами настольного `fastboot`. */
public sealed interface FastbootRequirementCheck {
    public val requirement: FastbootRequirement

    public data class Met(override val requirement: FastbootRequirement, val value: String) : FastbootRequirementCheck

    /** Требование для другого продукта: `IGNORE, product is X required only for Y`. */
    public data class Ignored(override val requirement: FastbootRequirement, val currentProduct: String) :
        FastbootRequirementCheck

    public data class NotMet(override val requirement: FastbootRequirement, val value: String) : FastbootRequirementCheck {
        /** `Device X is 'v'. Update requires 'a' or 'b'.` */
        public val message: String
            get() = "Device ${requirement.name} is '$value'. Update ${if (requirement.invert) "rejects" else "requires"} " +
                requirement.options.joinToString(" or ") { "'$it'" } + "."
    }

    /** `getvar` не ответил: `Could not getvar for 'X' (…)`. */
    public data class Unreadable(override val requirement: FastbootRequirement, val detail: String) :
        FastbootRequirementCheck
}

/**
 * Требования набора образов — `ParseRequirementLine` и `CheckRequirement` из
 * AOSP `fastboot/fastboot.cpp`.
 */
public object FastbootAndroidInfo {
    private val requireReject = Regex("""(require\s+|reject\s+)?\s*(\S+)\s*=\s*(.*)""")
    private val requireProduct = Regex("""require-for-product:\s*(\S+)\s+(\S+)\s*=\s*(.*)""")

    /** Разбор строки; `null` — `android-info.txt syntax error`. `board` читается как `product`. */
    public fun parseLine(line: String): FastbootRequirement? {
        val plain = requireReject.matchEntire(line)
        val forProduct = if (plain == null) requireProduct.matchEntire(line) else null
        val match = plain ?: forProduct ?: return null
        val (first, name, options) = match.destructured
        return FastbootRequirement(
            name = if (name == "board") "product" else name,
            product = if (forProduct != null) first else "",
            invert = plain != null && first.trim() == "reject",
            options = options.split("|").map { it.trim() },
        )
    }

    /**
     * Сверяет требование с устройством: значение совпало с одним из вариантов
     * или с префиксом варианта, кончающегося `*`; `reject` переворачивает итог.
     */
    public fun check(
        requirement: FastbootRequirement,
        currentProduct: String,
        read: (String) -> FastbootVariable,
    ): FastbootRequirementCheck {
        if (requirement.product.isNotEmpty() && requirement.product != currentProduct) {
            return FastbootRequirementCheck.Ignored(requirement, currentProduct)
        }
        val value = when (val variable = read(requirement.name)) {
            is FastbootVariable.Present -> variable.value
            is FastbootVariable.Unsupported -> return FastbootRequirementCheck.Unreadable(requirement, variable.detail)
            is FastbootVariable.Unavailable -> return FastbootRequirementCheck.Unreadable(requirement, variable.detail)
        }
        val match = requirement.options.any { option ->
            option == value || (option.endsWith('*') && value.startsWith(option.dropLast(1)))
        }
        return if (match != requirement.invert) {
            FastbootRequirementCheck.Met(requirement, value)
        } else {
            FastbootRequirementCheck.NotMet(requirement, value)
        }
    }
}
