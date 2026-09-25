package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

interface NarmestelederbehovRepository {
    suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov?

    suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult

    suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult
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
