package no.nav.syfo.narmestelederbehov.domain

import no.nav.syfo.ident.PersonIdent

data class ManagerContactInput(
    val personIdent: PersonIdent,
    val lastName: String,
    val email: String,
    val mobile: String,
)

data class NormalizedManagerContact(
    val personIdent: PersonIdent,
    val lastName: String,
    val email: EmailAddress,
    val mobile: PhoneNumber,
)

sealed interface ManagerContactNormalization {
    data class Valid(val manager: NormalizedManagerContact) : ManagerContactNormalization

    data class Invalid(val issues: List<ManagerContactValidationIssue>) : ManagerContactNormalization
}

data class ManagerContactValidationIssue(
    val field: ManagerContactField,
    val reason: ManagerContactValidationReason,
)

enum class ManagerContactField {
    MOBILE,
    EMAIL,
}

enum class ManagerContactValidationReason(val message: String) {
    PHONE_NUMBER_MUST_NOT_BE_BLANK("PhoneNumber must not be blank"),
    PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS("PhoneNumber must contain only digits, with an optional leading plus sign"),
    EMAIL_ADDRESS_MUST_NOT_BE_BLANK("EmailAddress must not be blank"),
    EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES("EmailAddress must not contain empty email entries"),
    EMAIL_ADDRESS_MUST_NOT_CONTAIN_WHITESPACE("EmailAddress must not contain whitespace"),
    EMAIL_ADDRESS_MUST_BE_VALID("EmailAddress must be a valid email address"),
}

fun ManagerContactInput.normalize(): ManagerContactNormalization {
    val normalizedMobile = mobile.replace(" ", "")
    val normalizedEmail = email.split(";").joinToString(";") { it.trim() }
    val issues = listOfNotNull(
        normalizedMobile.validationIssueForMobile(),
        normalizedEmail.validationIssueForEmail(),
    )

    return if (issues.isEmpty()) {
        ManagerContactNormalization.Valid(
            NormalizedManagerContact(
                personIdent = personIdent,
                lastName = lastName,
                email = EmailAddress(normalizedEmail),
                mobile = PhoneNumber(normalizedMobile),
            ),
        )
    } else {
        ManagerContactNormalization.Invalid(issues)
    }
}

@JvmInline
value class PhoneNumber private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): PhoneNumber {
            require(value.isNotBlank() && PHONE_NUMBER_REGEX.matches(value))
            return PhoneNumber(value)
        }
    }
}

@JvmInline
value class EmailAddress private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): EmailAddress {
            require(value.isNotBlank())
            require(value.split(";").none(String::isBlank))
            require(value.split(";").all(EMAIL_ADDRESS_REGEX::matches))
            return EmailAddress(value)
        }
    }
}

private val PHONE_NUMBER_REGEX = Regex("^\\+?\\d+$")
private val EMAIL_ADDRESS_REGEX = Regex(
    "^[A-Za-z0-9ÆØÅæøå._%+-]+@[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?(?:\\.[A-Za-z0-9ÆØÅæøå](?:[A-Za-z0-9ÆØÅæøå-]{0,61}[A-Za-z0-9ÆØÅæøå])?)+$",
)

private fun String.validationIssueForMobile(): ManagerContactValidationIssue? = when {
    isBlank() -> ManagerContactValidationIssue(
        ManagerContactField.MOBILE,
        ManagerContactValidationReason.PHONE_NUMBER_MUST_NOT_BE_BLANK,
    )

    !PHONE_NUMBER_REGEX.matches(this) -> ManagerContactValidationIssue(
        ManagerContactField.MOBILE,
        ManagerContactValidationReason.PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS,
    )

    else -> null
}

private fun String.validationIssueForEmail(): ManagerContactValidationIssue? {
    val emailParts = split(";")
    val reason = when {
        isBlank() -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_BE_BLANK
        emailParts.any(String::isBlank) -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_EMPTY_ENTRIES
        emailParts.any { it.any(Char::isWhitespace) } -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_WHITESPACE
        emailParts.any { !EMAIL_ADDRESS_REGEX.matches(it) } -> ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID
        else -> return null
    }
    return ManagerContactValidationIssue(ManagerContactField.EMAIL, reason)
}
