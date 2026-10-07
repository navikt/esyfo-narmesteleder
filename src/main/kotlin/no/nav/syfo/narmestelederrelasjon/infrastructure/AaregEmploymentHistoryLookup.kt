package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.AaregClientException
import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.narmestelederrelasjon.application.EmploymentHistoryFailureReason
import no.nav.syfo.narmestelederrelasjon.application.EmploymentHistoryLookup
import no.nav.syfo.narmestelederrelasjon.application.EmploymentHistoryResult
import no.nav.syfo.narmestelederrelasjon.domain.Employment
import kotlin.coroutines.cancellation.CancellationException

class AaregEmploymentHistoryLookup(private val client: AaregClient) : EmploymentHistoryLookup {
    override suspend fun findEmploymentHistory(personIdent: PersonIdent): EmploymentHistoryResult {
        val overview = try {
            client.getArbeidsforholdHistorikk(personIdent.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AaregClientException) {
            val reason = when (e.reason) {
                AaregClientException.Reason.PERSON_NOT_FOUND -> EmploymentHistoryFailureReason.PERSON_NOT_FOUND
                AaregClientException.Reason.UNAVAILABLE -> EmploymentHistoryFailureReason.UNAVAILABLE
            }
            return EmploymentHistoryResult.Failed(reason, cause = e)
        }
        val employments = overview.arbeidsforholdoversikter.map { employment ->
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
        return EmploymentHistoryResult.Found(employments)
    }
}
