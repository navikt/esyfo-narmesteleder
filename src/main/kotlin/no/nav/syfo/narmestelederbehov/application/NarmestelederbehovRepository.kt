package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

interface NarmestelederbehovRepository {
    suspend fun findDetails(id: NarmestelederbehovId): NarmestelederbehovDetails?

    suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName)

    suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov?

    /** Open statuses are BEHOV_CREATED and DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION. */
    suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId>

    suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult

    suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult

    /** Returns [CreateBehovResult.AlreadyExists] when the employee already has an open behov in the organization. */
    suspend fun create(behov: NewNarmestelederbehov): CreateBehovResult
}

data class NewNarmestelederbehov(
    val employee: Employee,
    val mainOrganizationNumber: String,
    val manager: PersonIdent?,
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
