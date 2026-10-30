package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
data class AltinnAuthorizationResponse(
    val response: List<DecisionResult>,
)

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
data class DecisionResult(
    val decision: AuthorizationDecision,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("Status")
    val status: DecisionStatus? = null,
)

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
data class DecisionStatus(
    @JsonProperty("StatusCode")
    val statusCode: DecisionStatusCode? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("StatusMessage")
    val statusMessage: String? = null,
)

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
data class DecisionStatusCode(
    @JsonProperty("Value")
    val value: String? = null,
)

/**
 * Authorization decision returned by Altinn PDP.
 *
 * Normal either Permit or NotApplicable are returned for typical authorization checks.
 * Indeterminate: is rarely returned, we typically get it when there is an orgnumber sendt, which is not existing or deleted a long time ago.
 */
enum class AuthorizationDecision {
    Permit,
    Indeterminate,
    NotApplicable,
    Deny,
}
