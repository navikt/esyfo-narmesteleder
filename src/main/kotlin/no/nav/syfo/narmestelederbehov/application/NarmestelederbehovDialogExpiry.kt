package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.platform.upstream.UpstreamFailure
import java.time.Instant
import java.util.UUID

fun interface NarmestelederbehovDialogExpiry {
    suspend fun expire(dialogId: UUID, expirationTime: Instant): DialogExpiry
}

sealed interface DialogExpiry {
    data object Expired : DialogExpiry
    data class Unavailable(val failure: UpstreamFailure) : DialogExpiry
}
