package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import java.time.Instant

/** Open statuses are BEHOV_CREATED and DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION. */
interface OpenNarmestelederbehovRepository {
    suspend fun findOpen(organizationNumber: OrganizationNumber, createdAfter: Instant, limit: Int): List<NarmestelederbehovDetails>

    suspend fun countOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Long
}
