package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.platform.upstream.UpstreamFailure
import java.util.UUID

fun interface NarmestelederbehovDialog {
    suspend fun complete(dialogId: UUID): DialogCompletion
}

sealed interface DialogCompletion {
    data object Completed : DialogCompletion
    data class Unavailable(val failure: UpstreamFailure) : DialogCompletion
}

sealed interface DialogportenCompletionAttempt {
    data object Completed : DialogportenCompletionAttempt
    data object Failed : DialogportenCompletionAttempt
    data object NotApplicable : DialogportenCompletionAttempt
}
