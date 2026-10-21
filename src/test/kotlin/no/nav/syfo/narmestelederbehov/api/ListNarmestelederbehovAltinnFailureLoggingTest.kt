package no.nav.syfo.narmestelederbehov.api

import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.ALTINN_FAILURE_ORG_NUMBER
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.altinnFailureLoggingContract
import java.time.Instant

class ListNarmestelederbehovAltinnFailureLoggingTest :
    FunSpec({
        altinnFailureLoggingContract(
            endpoint = "access check",
            path = "/linemanager/requirement?orgNumber=$ALTINN_FAILURE_ORG_NUMBER&createdAfter=1970-01-01T00:00:00Z",
            capabilityLoggers = listOf(ListNarmestelederbehovUseCase::class.java.name),
        ) { upstream ->
            registerListNarmestelederbehovApi(
                ListNarmestelederbehovUseCase(
                    UnreachableOpenRepository(),
                    upstream.organizationAccess,
                    NarmestelederbehovEmployeeName(mockk()) { error("Name lookup must not be called") },
                ),
                upstream.authClient,
            )
        }
    })

private class UnreachableOpenRepository : OpenNarmestelederbehovRepository {
    override suspend fun findOpen(organizationNumber: OrganizationNumber, createdAfter: Instant, limit: Int): List<NarmestelederbehovDetails> = error("Repository must not be called after access failure")

    override suspend fun countOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Long = error("Repository must not be called after access failure")
}
