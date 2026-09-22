package no.nav.syfo.narmestelederbehov.domain

private val PHONE_NUMBER_REGEX = Regex("^\\+?\\d+$")

@JvmInline
value class PhoneNumber(val value: String) {
    init {
        validationReason(value)?.let { throw IllegalArgumentException(it.message) }
    }

    companion object {
        internal fun validationReason(value: String): ManagerContactValidationReason? = when {
            value.isBlank() -> ManagerContactValidationReason.PHONE_NUMBER_MUST_NOT_BE_BLANK
            !PHONE_NUMBER_REGEX.matches(value) -> ManagerContactValidationReason.PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS
            else -> null
        }
    }
}
