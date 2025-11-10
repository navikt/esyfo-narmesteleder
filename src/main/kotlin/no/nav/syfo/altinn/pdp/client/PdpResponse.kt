package no.nav.syfo.altinn.pdp.client

data class PdpResponse(
    val response: List<DecisionResult>,
)

data class DecisionResult(
    val decision: Decision,
)

enum class Decision {
    Permit,
    Indeterminate,
    NotApplicable,
    Deny,
}

fun PdpResponse.resultat() = response.first().decision

fun PdpResponse.harTilgang(): Boolean = resultat() == Decision.Permit
