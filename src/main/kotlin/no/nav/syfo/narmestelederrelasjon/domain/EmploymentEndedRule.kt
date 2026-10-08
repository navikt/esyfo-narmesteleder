package no.nav.syfo.narmestelederrelasjon.domain

import no.nav.syfo.ident.OrganizationNumber
import java.time.LocalDate

data class Employment(
    val workplaceOrganizationNumber: OrganizationNumber?,
    val endDate: LocalDate?,
    val startDate: LocalDate? = null,
)

enum class EmploymentEndedDecision {
    KEEP,
    REVOKE,
}

object EmploymentEndedRule {
    fun evaluate(
        organizationNumber: OrganizationNumber,
        employments: List<Employment>,
        today: LocalDate,
    ): EmploymentEndedDecision {
        val cutoff = today.minusMonths(4)
        val hasQualifyingEmployment = employments.any { employment ->
            employment.workplaceOrganizationNumber == organizationNumber &&
                (employment.endDate == null || employment.endDate >= cutoff)
        }
        return if (hasQualifyingEmployment) EmploymentEndedDecision.KEEP else EmploymentEndedDecision.REVOKE
    }
}
