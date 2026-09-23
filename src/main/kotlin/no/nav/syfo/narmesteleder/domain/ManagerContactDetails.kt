package no.nav.syfo.narmesteleder.domain

data class ContactValidationIssue(
    val fieldName: String,
    val reason: String,
    val field: ContactField,
)

enum class ContactField { MOBILE, EMAIL }

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
                    field = ContactField.MOBILE,
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
                    field = ContactField.EMAIL,
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
