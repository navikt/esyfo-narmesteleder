package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import no.nav.syfo.platform.observability.applicationEvent
import org.slf4j.event.Level

internal data class PdpAuthorizationNotPermitContext(
    val request: AltinnAuthorizationRequest,
    val result: DecisionResult,
)

// Logged to team logs only: the request contains the user's fnr.
internal val pdpAuthorizationNotPermit = applicationEvent<PdpAuthorizationNotPermitContext>(
    name = "altinn_pdp_authorization_not_permit",
    level = Level.WARN,
    message = "Altinn PDP authorization decision was not Permit",
    upstream = ALTINN_PDP.value,
    fields = mapOf(
        "pdp_request" to { it.request },
        "pdp_response" to { it.result },
    ),
)
