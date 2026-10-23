package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID

data class NarmestelederbehovDialogReference(val id: NarmestelederbehovId, val dialogId: UUID)

data class PendingNarmestelederbehovDialog(
    val id: NarmestelederbehovId,
    val employee: Employee,
    val created: Instant,
    val dialogId: UUID?,
)

data class NewNarmestelederbehov(
    val employee: Employee,
    val mainOrganizationNumber: String,
    val manager: PersonalIdentificationNumber?,
    val reason: BehovReason,
    val status: BehovStatus,
    val revokedRelationId: UUID?,
)

sealed interface CreateBehovResult {
    data class Created(val id: NarmestelederbehovId) : CreateBehovResult
    data object AlreadyExists : CreateBehovResult
}

sealed interface MarkFulfilledResult {
    data class Marked(val id: NarmestelederbehovId, val dialogId: UUID?) : MarkFulfilledResult
    data object Missing : MarkFulfilledResult
}

sealed interface MarkDialogCompletedResult {
    data object Marked : MarkDialogCompletedResult

    /** The behov is missing or another writer changed its status; nothing is overwritten. */
    data object NotFulfilled : MarkDialogCompletedResult
}

sealed interface MarkDialogCreatedResult {
    data object Marked : MarkDialogCreatedResult

    /** The behov is missing or has left BEHOV_CREATED; nothing is overwritten. */
    data object NotCreated : MarkDialogCreatedResult
}
