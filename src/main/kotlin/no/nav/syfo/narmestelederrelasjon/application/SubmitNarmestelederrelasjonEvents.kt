package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import org.slf4j.event.Level

internal data class ContactValidationRejectedDetails(val validationIssues: List<ManagerContactValidationIssue>)

internal val contactValidationRejected = applicationEvent<ContactValidationRejectedDetails>(
    name = "contact_validation_rejected",
    level = Level.WARN,
    message = "Manager contact fields failed validation",
    fields = mapOf(
        "validation_issues" to {
            it.validationIssues.map { issue ->
                mapOf("field" to issue.field.name, "reason" to "INVALID_FORMAT")
            }
        },
    ),
)
