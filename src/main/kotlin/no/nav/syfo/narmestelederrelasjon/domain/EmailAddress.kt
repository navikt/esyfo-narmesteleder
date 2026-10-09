package no.nav.syfo.narmestelederrelasjon.domain

private const val LOCAL_PART_ATOM = "[A-Za-z0-9ÆØÅæøå_%+-]+"
private const val DOMAIN_LABEL = "[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?"

/**
 * Matches one address that can receive an external email notification to employers.
 *
 * The rule is the intersection of the two validations the address passes on its way:
 * - arbeidsgiver-notifikasjon-produsent-api, `Validators.Email`, rejects every local-part symbol
 *   except `.`, `_`, `%`, `+` and `-`.
 * - Altinn 3 notifications, `RecipientRules.IsValidEmail`, allows only Æ, Ø and Å as non-ASCII letters,
 *   rejects leading, trailing and consecutive dots in the local-part, allows at most ten domain labels
 *   and requires a top-level domain of 2–14 ASCII letters.
 *
 * See docs/email-address-rule.md.
 */
private val EMAIL_ADDRESS_REGEX = Regex(
    "^$LOCAL_PART_ATOM(?:\\.$LOCAL_PART_ATOM)*@(?:$DOMAIN_LABEL\\.){1,9}[A-Za-z]{2,14}$",
)

@JvmInline
value class EmailAddress(val value: String) {
    init {
        validationReason(value)?.let { throw IllegalArgumentException(it.message) }
    }

    companion object {
        fun fromSeparatedList(value: String): List<EmailAddress> = splitEmailAddresses(value).map(::EmailAddress)

        fun parseSeparatedList(value: String): ParsedEmailAddresses {
            val (valid, discarded) = splitEmailAddresses(value).partition { validationReason(it) == null }
            return ParsedEmailAddresses(valid.map(::EmailAddress), discarded.size)
        }

        internal fun validationReason(value: String): ManagerContactValidationReason? {
            if (value.isBlank()) return ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_BE_BLANK

            // Report the first failing entry so the API error stays stable for multi-address input.
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

data class ParsedEmailAddresses(
    val validEmailAddresses: List<EmailAddress>,
    val discardedEmailAddressCount: Int,
)

private fun splitEmailAddresses(value: String): List<String> = value
    .split(",", ";")
    .map(String::trim)
    .filter(String::isNotEmpty)
