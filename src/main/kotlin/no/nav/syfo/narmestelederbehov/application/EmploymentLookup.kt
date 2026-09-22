package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

fun interface EmploymentLookup {
    suspend fun hasEmployment(
        personIdent: PersonIdent,
        organizationNumber: OrganizationNumber,
    ): Boolean
}
