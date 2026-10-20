package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID

fun interface PendingNarmestelederbehovRepository {
    /** BEHOV_CREATED strictly older than [createdBefore], oldest first. */
    suspend fun findPendingDialogs(createdBefore: Instant, limit: Int): List<PendingNarmestelederbehovDialog>
}

data class PendingNarmestelederbehovDialog(
    val id: NarmestelederbehovId,
    val employee: Employee,
    val created: Instant,
    val dialogId: UUID?,
)
