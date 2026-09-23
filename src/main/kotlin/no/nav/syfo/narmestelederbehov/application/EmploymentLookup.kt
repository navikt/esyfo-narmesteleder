package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

fun interface EmploymentLookup {
    suspend fun findEmployment(
        personIdent: PersonIdent,
        organizationNumber: OrganizationNumber,
    ): EmploymentResult
}

enum class EmploymentResult {
    IN_ORGANIZATION,
    NONE,
    NOT_IN_ORGANIZATION,
}
