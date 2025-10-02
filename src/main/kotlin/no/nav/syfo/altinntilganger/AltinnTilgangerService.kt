package no.nav.syfo.altinntilganger

import no.nav.syfo.altinntilganger.client.IAltinnTilgangerClient
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.util.logger

class AltinnTilgangerService(
    val altinnTilgangerClient: IAltinnTilgangerClient,
) {
    suspend fun validateTilgangToOrganisasjon(
        brukerPrincipal: BrukerPrincipal,
        orgnummer: String,
    ) {
        try {
            val tilganger = altinnTilgangerClient.hentTilganger(brukerPrincipal)
            if (tilganger?.orgNrTilTilganger[orgnummer]?.contains(OPPRETT_NL_REALASJON_RESOURCE) != true)
                throw ApiErrorException.ForbiddenException("Bruker har ikke tilgang til organisasjon $orgnummer")
        } catch (e: UpstreamRequestException) {
            logger.error("Feil ved henting av tilgang til organisasjon $orgnummer", e)
            throw ApiErrorException.InternalServerErrorException("Feil ved henting av altinn-tilganger")
        }
    }

    companion object {
        const val OPPRETT_NL_REALASJON_RESOURCE = "4596:1" // Access resource in Altinn2 to access NL relasjon
        private val logger = logger()
    }
}
