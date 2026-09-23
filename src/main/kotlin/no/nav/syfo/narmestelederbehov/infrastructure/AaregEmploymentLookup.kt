package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.getForOrgnummer
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.EmploymentLookup
import no.nav.syfo.narmestelederbehov.application.EmploymentResult

class AaregEmploymentLookup(private val service: AaregService) : EmploymentLookup {
    override suspend fun findEmployment(personIdent: PersonIdent, organizationNumber: OrganizationNumber): EmploymentResult {
        val employment = service.findArbeidsforholdByPersonIdent(personIdent.value)
        return when {
            employment.isEmpty() -> EmploymentResult.NONE
            employment.getForOrgnummer(organizationNumber.value) == null -> EmploymentResult.NOT_IN_ORGANIZATION
            else -> EmploymentResult.IN_ORGANIZATION
        }
    }
}
