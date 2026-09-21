package no.nav.syfo.narmestelederbehov.domain

private val EMAIL_ADDRESS_REGEX = Regex(
    "^[A-Za-z0-9ÆØÅæøå._%+-]+@[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?(?:\\.[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?)+$",
)

@JvmInline
value class EmailAddress(val value: String) {
    init {
        validationReason(value)?.let { throw IllegalArgumentException(it.message) }
    }

    companion object {
        internal fun validationReason(value: String): ManagerContactValidationReason? {
            if (value.isBlank()) return ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_BE_BLANK

            // Preserve the first failing entry and rule used by the existing API validation.
            return value.split(";").firstNotNullOfOrNull { emailPart ->
                when {
                    emailPart.isBlank() -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES
                    emailPart.any(Char::isWhitespace) -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_WHITESPACE
                    !EMAIL_ADDRESS_REGEX.matches(emailPart) -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID
                    else -> null
                }
            }
        }
    }
}
