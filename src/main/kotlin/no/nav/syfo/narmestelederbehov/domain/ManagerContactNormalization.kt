package no.nav.syfo.narmestelederbehov.domain

import no.nav.syfo.ident.PersonIdent

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
