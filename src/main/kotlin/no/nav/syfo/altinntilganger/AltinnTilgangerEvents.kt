package no.nav.syfo.altinntilganger

import no.nav.esyfo.observability.Event
import no.nav.syfo.application.exception.UpstreamExceptionType
import org.slf4j.event.Level

internal enum class AltinnTilgangerOperation(
    val value: String,
) {
    LOOKUP_ORGANIZATION_ACCESS("hent_altinn_tilgang_for_orgnummer"),
    LIST_ACCESSIBLE_ORGANIZATIONS("hent_tilgjengelige_organisasjoner"),
    ;

    val failureEvent = Event<AltinnTilgangerFailure>(
        name = "altinn_tilganger_lookup_failed",
        level = Level.ERROR,
        message = "AltinnTilganger lookup failed",
        operation = value,
        errorCodeFrom = { it.errorCode.value },
        fields = altinnFailureFields,
    )
}

private val altinnFailureFields: Map<String, (AltinnTilgangerFailure) -> Any?> = mapOf(
    "exception_type" to { it.exceptionType?.logValue },
    "cause_type" to { it.causeType },
    "cause_types" to { it.causeTypes },
    "failure_kind" to { it.failureKind },
    "upstream" to { it.upstream },
    "upstream_error_code" to { it.upstreamErrorCode },
    "upstream_status" to { it.upstreamStatus },
)

internal data class AltinnTilgangerFailure(
    val errorCode: AltinnTilgangerErrorCode,
    val exceptionType: UpstreamExceptionType? = null,
    val causeType: String? = null,
    val causeTypes: List<String>? = null,
    val failureKind: String? = null,
    val upstream: String = "arbeidsgiver-altinn-tilganger",
    val upstreamErrorCode: String? = null,
    val upstreamStatus: Int? = null,
)

internal enum class AltinnTilgangerErrorCode(
    val value: String,
) {
    UPSTREAM_UNEXPECTED_REDIRECT("ALTINN_TILGANGER_UPSTREAM_UNEXPECTED_REDIRECT"),
    UPSTREAM_UNAUTHORIZED("ALTINN_TILGANGER_UPSTREAM_UNAUTHORIZED"),
    UPSTREAM_FORBIDDEN("ALTINN_TILGANGER_UPSTREAM_FORBIDDEN"),
    UPSTREAM_NOT_FOUND("ALTINN_TILGANGER_UPSTREAM_NOT_FOUND"),
    UPSTREAM_RATE_LIMITED("ALTINN_TILGANGER_UPSTREAM_RATE_LIMITED"),
    UPSTREAM_CLIENT_ERROR("ALTINN_TILGANGER_UPSTREAM_CLIENT_ERROR"),
    UPSTREAM_SERVER_ERROR("ALTINN_TILGANGER_UPSTREAM_SERVER_ERROR"),
    UPSTREAM_TRANSPORT_FAILURE("ALTINN_TILGANGER_UPSTREAM_TRANSPORT_FAILURE"),
    UPSTREAM_RESPONSE_FAILURE("ALTINN_TILGANGER_UPSTREAM_RESPONSE_FAILURE"),
    TOKEN_EXCHANGE_FAILED("ALTINN_TILGANGER_TOKEN_EXCHANGE_FAILED"),
    ERROR_RESPONSE("ALTINN_TILGANGER_ERROR_RESPONSE"),
}
