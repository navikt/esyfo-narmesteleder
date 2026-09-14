package no.nav.syfo.altinntilganger

import no.nav.esyfo.observability.Event
import no.nav.syfo.application.exception.UpstreamExceptionType
import no.nav.syfo.application.exception.UpstreamFailureStage
import org.slf4j.event.Level

/** Closed, code-owned catalog for runtime errors emitted by [AltinnTilgangerService]. */
internal enum class AltinnTilgangerRuntimeEvent(
    val value: String,
) {
    LOOKUP_FAILED("altinn_tilganger_lookup_failed"),
}

internal enum class AltinnTilgangerOperation(
    val value: String,
) {
    LOOKUP_ORGANIZATION_ACCESS("hent_altinn_tilgang_for_orgnummer"),
    LIST_ACCESSIBLE_ORGANIZATIONS("hent_tilgjengelige_organisasjoner"),
    ;

    val failureEvent = Event<AltinnTilgangerFailure>(
        name = AltinnTilgangerRuntimeEvent.LOOKUP_FAILED.value,
        level = Level.ERROR,
        message = "AltinnTilganger lookup failed",
        operation = value,
        errorCodeFrom = { it.errorCode.value },
        fields = mapOf(
            "exception_type" to { it.exceptionType?.logValue },
            "cause_type" to { it.causeType },
            "failure_stage" to { it.failureStage?.logValue },
            "upstream_status" to { it.upstreamStatus },
        ),
    )
}

internal data class AltinnTilgangerFailure(
    val errorCode: AltinnTilgangerErrorCode,
    val exceptionType: UpstreamExceptionType? = null,
    val causeType: String? = null,
    val failureStage: UpstreamFailureStage? = null,
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
