package no.nav.syfo.narmestelederbehov.domain

import no.nav.syfo.ident.PersonIdent

data class ManagerContactInput(
    val personIdent: PersonIdent,
    val lastName: String,
    val email: String,
    val mobile: String,
)

fun ManagerContactInput.normalize(): ManagerContactNormalization {
    val normalizedMobile = mobile.replace(" ", "")
    val normalizedEmail = email.split(";").joinToString(";") { it.trim() }
    val issues = listOfNotNull(
        PhoneNumber.validationReason(normalizedMobile)?.let { ManagerContactValidationIssue(ManagerContactField.MOBILE, it) },
        EmailAddress.validationReason(normalizedEmail)?.let { ManagerContactValidationIssue(ManagerContactField.EMAIL, it) },
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
