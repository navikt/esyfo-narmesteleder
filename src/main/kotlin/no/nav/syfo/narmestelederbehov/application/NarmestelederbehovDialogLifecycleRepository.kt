package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID

interface NarmestelederbehovDialogLifecycleRepository {
    /** Up to 100 fulfilled behov with dialogs, strictly older than [createdBefore], oldest first. */
    suspend fun findFulfilledDialogs(createdBefore: Instant): List<NarmestelederbehovDialogReference>

    /** Up to 100 BEHOV_EXPIRED or ERROR behov with dialogs and no expiry marker, oldest first. */
    suspend fun findDialogsToExpire(): List<NarmestelederbehovDialogReference>

    suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant)
}

data class NarmestelederbehovDialogReference(val id: NarmestelederbehovId, val dialogId: UUID)
