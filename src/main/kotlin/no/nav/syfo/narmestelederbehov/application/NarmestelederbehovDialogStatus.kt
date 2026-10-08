package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId

fun interface NarmestelederbehovDialogStatus {
    suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult
}

sealed interface MarkDialogCompletedResult {
    data object Marked : MarkDialogCompletedResult

    /** The behov is missing or another writer changed its status; nothing is overwritten. */
    data object NotFulfilled : MarkDialogCompletedResult
}
