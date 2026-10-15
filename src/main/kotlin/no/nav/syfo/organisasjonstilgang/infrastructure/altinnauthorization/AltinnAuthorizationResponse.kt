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
    val decision: Decision,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("Status")
    val status: DecisionStatus? = null,
)

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
data class DecisionStatus(
    @JsonProperty("StatusCode")
    val statusCode: DecisionStatusCode? = null,
)

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
data class DecisionStatusCode(
    @JsonProperty("Value")
    val value: String? = null,
)

enum class Decision {
    Permit,
    Indeterminate,
    NotApplicable,
    Deny,
}
