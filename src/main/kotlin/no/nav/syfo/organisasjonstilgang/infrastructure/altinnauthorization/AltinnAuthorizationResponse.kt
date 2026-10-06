package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

data class AltinnAuthorizationResponse(
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

fun AltinnAuthorizationResponse.result() = response.first().decision
