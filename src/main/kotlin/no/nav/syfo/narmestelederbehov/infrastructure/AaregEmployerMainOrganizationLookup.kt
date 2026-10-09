package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.narmestelederbehov.application.EmployerMainOrganizationLookup
import no.nav.syfo.narmestelederbehov.application.MainOrganizationResult
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.platform.upstream.UpstreamResult

class AaregEmployerMainOrganizationLookup(private val client: AaregClient) : EmployerMainOrganizationLookup {
    override suspend fun findMainOrganization(employee: Employee): MainOrganizationResult = when (val result = client.getArbeidsforhold(employee.personIdent.value)) {
        is UpstreamResult.Failure -> MainOrganizationResult.Unavailable(result.failure)
        is UpstreamResult.Success -> {
            val employment = result.value.arbeidsforholdoversikter
                .firstOrNull { it.arbeidssted.getOrgnummer() == employee.organizationNumber.value }
            when {
                employment == null -> MainOrganizationResult.EmploymentMissing
                else -> employment.opplysningspliktig.getJuridiskOrgnummer()
                    ?.let { MainOrganizationResult.Found(it) }
                    ?: MainOrganizationResult.MainOrganizationMissing
            }
        }
    }
}
