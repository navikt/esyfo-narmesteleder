package no.nav.syfo.narmesteleder.domain

data class ContactValidationIssue(
    val fieldName: String,
    val reason: String,
)

data class ManagerContactDetailsValidation(
    val manager: Manager,
    val issues: List<ContactValidationIssue>,
)

fun Manager.normalizeContactDetails(): ManagerContactDetailsValidation {
    val issues = mutableListOf<ContactValidationIssue>()

    val normalizedMobile = PhoneNumber.parse(mobile)
        .onFailure {
            issues.add(
                ContactValidationIssue(
                    fieldName = "mobile",
                    reason = it.message ?: "Invalid phone number",
                )
            )
        }
        .getOrNull()
        ?.value
        ?: mobile

    val normalizedEmail = EmailAddress.parse(email)
        .onFailure {
            issues.add(
                ContactValidationIssue(
                    fieldName = "email",
                    reason = it.message ?: "Invalid email address",
                )
            )
        }
        .getOrNull()
        ?.value
        ?: email

    return ManagerContactDetailsValidation(
        manager = copy(
            mobile = normalizedMobile,
            email = normalizedEmail,
        ),
        issues = issues,
    )
}
