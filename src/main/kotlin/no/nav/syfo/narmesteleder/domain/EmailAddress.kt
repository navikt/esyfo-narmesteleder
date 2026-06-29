package no.nav.syfo.narmesteleder.domain

/*
 * Matches one email address after semicolon-separated input has been split and trimmed.
 * The local-part allows letters, digits, common email symbols, plus signs and Norwegian letters.
 * Each domain label must start and end with a letter or digit, may contain hyphens in the middle,
 * and the address must contain at least one dot in the domain.
 */
private val EMAIL_ADDRESS_REGEX = Regex(
    pattern =
    "^[A-Za-z0-9ÆØÅæøå._%+-]+@[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?(?:\\.[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?)+\$"
)

@JvmInline
value class EmailAddress(val value: String) {
    init {
        require(value.isNotBlank()) {
            "EmailAddress must not be blank"
        }
        value
            .split(";")
            .forEach { emailPart ->
                require(emailPart.isNotBlank()) {
                    "EmailAddress must not contain empty email entries"
                }
                require(emailPart.none(Char::isWhitespace)) {
                    "EmailAddress must not contain whitespace"
                }
                require(EMAIL_ADDRESS_REGEX.matches(emailPart)) {
                    "EmailAddress must be a valid email address"
                }
            }
    }

    companion object {
        fun parse(value: String): Result<EmailAddress> = runCatching {
            EmailAddress(
                value = value
                    .split(";")
                    .map(String::trim)
                    .joinToString(";"),
            )
        }
    }
}
