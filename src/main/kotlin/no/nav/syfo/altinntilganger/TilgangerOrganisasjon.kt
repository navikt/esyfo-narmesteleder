package no.nav.syfo.altinntilganger
data class TilgangerOrganisasjon(
    val orgnr: String,
    val navn: String,
    val underenheter: List<TilgangerOrganisasjon>,
)
