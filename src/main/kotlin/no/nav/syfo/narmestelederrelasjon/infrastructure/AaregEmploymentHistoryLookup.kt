package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.aareg.AaregArbeidsforholdOversikt
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.narmestelederrelasjon.application.EmploymentHistoryLookup
import no.nav.syfo.narmestelederrelasjon.application.EmploymentHistoryResult
import no.nav.syfo.narmestelederrelasjon.domain.Employment
import no.nav.syfo.platform.upstream.UpstreamResult

class AaregEmploymentHistoryLookup(private val client: AaregClient) : EmploymentHistoryLookup {
    override suspend fun findEmploymentHistory(personIdent: PersonIdent): EmploymentHistoryResult = when (val result = client.getArbeidsforholdHistorikk(personIdent.value)) {
        is UpstreamResult.Failure -> EmploymentHistoryResult.Unavailable(result.failure)
        is UpstreamResult.Success -> EmploymentHistoryResult.Found(result.value.toEmployments())
    }

    private fun AaregArbeidsforholdOversikt.toEmployments(): List<Employment> = arbeidsforholdoversikter.map { employment ->
        Employment(
            workplaceOrganizationNumber = employment.arbeidssted
                .takeIf { it.type == ArbeidsstedType.Underenhet }
                ?.getOrgnummer()
                ?.let { organizationNumber ->
                    try {
                        OrganizationNumber(organizationNumber)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                },
            startDate = employment.startdato,
            endDate = employment.sluttdato,
        )
    }
}
