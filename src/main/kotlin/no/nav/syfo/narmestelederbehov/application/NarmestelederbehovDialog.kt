package no.nav.syfo.narmestelederbehov.application

import java.util.UUID

fun interface NarmestelederbehovDialog {
    suspend fun complete(dialogId: UUID)
}

sealed interface DialogportenCompletionAttempt {
    data object Completed : DialogportenCompletionAttempt
    data object Failed : DialogportenCompletionAttempt
    data object NotApplicable : DialogportenCompletionAttempt
}
