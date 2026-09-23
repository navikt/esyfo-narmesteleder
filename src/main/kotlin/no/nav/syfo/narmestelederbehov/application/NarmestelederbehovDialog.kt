package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId

fun interface NarmestelederbehovDialog {
    suspend fun attemptCompletion(id: NarmestelederbehovId): DialogportenCompletionAttempt
}

sealed interface DialogportenCompletionAttempt {
    data object Completed : DialogportenCompletionAttempt
    data object Failed : DialogportenCompletionAttempt
    data object NotApplicable : DialogportenCompletionAttempt
}
