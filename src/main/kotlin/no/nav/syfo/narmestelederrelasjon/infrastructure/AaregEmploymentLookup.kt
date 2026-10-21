package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentLookup
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.platform.upstream.UpstreamResult

class AaregEmploymentLookup(private val client: AaregClient) : EmploymentLookup {
    override suspend fun findEmployment(personIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber): EmploymentResult = when (val result = client.getArbeidsforhold(personIdent.value)) {
        is UpstreamResult.Failure -> EmploymentResult.Unavailable(result.failure)
        is UpstreamResult.Success -> {
            val employment = result.value.arbeidsforholdoversikter.mapNotNull { it.arbeidssted.getOrgnummer() }
            when {
                employment.isEmpty() -> EmploymentResult.None
                organizationNumber.value !in employment -> EmploymentResult.NotInOrganization
                else -> EmploymentResult.InOrganization
            }
        }
    }
}
