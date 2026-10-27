package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.ktor.http.HttpStatusCode
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentLookup
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult

class AaregEmploymentLookup(private val client: AaregClient) : EmploymentLookup {
    override suspend fun findEmployment(personIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber): EmploymentResult = when (val result = client.getArbeidsforhold(personIdent.value)) {
        is UpstreamResult.Failure -> if (result.failure.isUnknownPersonIdent()) {
            EmploymentResult.None
        } else {
            EmploymentResult.Unavailable(result.failure)
        }

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

// AAREG answers 404 on arbeidsforholdoversikt when it does not know the personident.
private fun UpstreamFailure.isUnknownPersonIdent(): Boolean = stage == UpstreamFailureStage.RESPONSE && status == HttpStatusCode.NotFound.value
