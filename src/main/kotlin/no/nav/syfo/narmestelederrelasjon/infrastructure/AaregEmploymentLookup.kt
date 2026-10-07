package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.AaregClientException
import no.nav.syfo.narmestelederrelasjon.application.EmploymentLookup
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult

class AaregEmploymentLookup(private val client: AaregClient) : EmploymentLookup {
    override suspend fun findEmployment(personIdent: PersonIdent, organizationNumber: OrganizationNumber): EmploymentResult {
        val employment = findOrganizationNumbers(personIdent)
        return when {
            employment.isEmpty() -> EmploymentResult.NONE
            organizationNumber.value !in employment -> EmploymentResult.NOT_IN_ORGANIZATION
            else -> EmploymentResult.IN_ORGANIZATION
        }
    }

    private suspend fun findOrganizationNumbers(personIdent: PersonIdent): List<String> {
        val overview = try {
            client.getArbeidsforhold(personIdent = personIdent.value)
        } catch (e: AaregClientException) {
            throw ApiErrorException.InternalServerErrorException(
                errorMessage = "Could not fetch employment status fra Aareg",
                cause = e,
                type = ErrorType.UPSTREAM_SERVICE_UNAVAILABLE,
            )
        }
        return overview.arbeidsforholdoversikter.mapNotNull { it.arbeidssted.getOrgnummer() }
    }
}
