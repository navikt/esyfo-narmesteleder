package no.nav.syfo.narmestelederrelasjon.application

import no.nav.esyfo.observability.apiRequestRejected
import java.util.UUID

internal data class RevokeAccessRejectedDetails(
    val rejectionReason: String,
    val narmestelederId: UUID,
    val principalType: String,
)

internal val revokeAccessRejected = apiRequestRejected<RevokeAccessRejectedDetails>(
    operation = "revoke_linemanager",
    message = "Caller lacks access to revoke the nearest leader relation",
    reason = { it.rejectionReason },
    fields = mapOf(
        "narmesteleder_id" to { it.narmestelederId.toString() },
        "principal_type" to { it.principalType },
    ),
)
