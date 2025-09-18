package no.nav.syfo.altinntilganger.client

import no.nav.syfo.application.auth.BrukerPrincipal

class AltinnTilgangerService(
    val altinnTilgangerClient: IAltinnTilgangerClient,
) {
    suspend fun harTilgangTilOrganisasjon(
        brukerPrincipal: BrukerPrincipal,
        orgnummer: String,
    ): Boolean {
        val onsketTilgang = "nav_sosialtjenester_digisos-avtale"
        val tilganger = altinnTilgangerClient.hentTilganger(brukerPrincipal)
        return tilganger?.orgNrTilTilganger[orgnummer]?.contains(onsketTilgang) ?: false
    }
}
