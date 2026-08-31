package no.nav.syfo.altinntilganger

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
}

internal enum class AltinnTilgangerErrorCode(
    val value: String,
) {
    UPSTREAM_4XX("ALTINN_TILGANGER_UPSTREAM_4XX"),
    UPSTREAM_5XX("ALTINN_TILGANGER_UPSTREAM_5XX"),
    UPSTREAM_FAILURE("ALTINN_TILGANGER_UPSTREAM_FAILURE"),
    ERROR_RESPONSE("ALTINN_TILGANGER_ERROR_RESPONSE"),
}
