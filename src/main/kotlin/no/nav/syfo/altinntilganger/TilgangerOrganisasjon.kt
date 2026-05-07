package no.nav.syfo.altinntilganger

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TilgangerOrganisasjon(
    val orgnr: String,
    val navn: String,
    val underenheter: List<TilgangerOrganisasjon>,
)
