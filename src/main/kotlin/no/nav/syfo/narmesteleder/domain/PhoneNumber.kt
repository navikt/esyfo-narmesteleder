package no.nav.syfo.narmesteleder.domain

private val PHONE_NUMBER_REGEX = Regex("^\\+?\\d+$")

@JvmInline
value class PhoneNumber(val value: String) {
    init {
        require(value.isNotBlank()) {
            "PhoneNumber must not be blank"
        }
        require(PHONE_NUMBER_REGEX.matches(value)) {
            "PhoneNumber must contain only digits, with an optional leading plus sign"
        }
    }

    companion object {
        fun parse(value: String): Result<PhoneNumber> = runCatching {
            PhoneNumber(
                value = value.replace(" ", ""),
            )
        }
    }
}
