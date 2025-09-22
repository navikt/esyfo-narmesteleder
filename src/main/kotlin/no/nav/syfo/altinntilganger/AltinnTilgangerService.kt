package no.nav.syfo.altinntilganger

import io.ktor.client.plugins.ResponseException
import io.ktor.server.plugins.BadRequestException
import no.nav.syfo.altinntilganger.client.IAltinnTilgangerClient
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.exception.InternalServerErrorException
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
            logger.info("Hentet altinn-tilganger for bruker: $tilganger")
            if (tilganger?.orgNrTilTilganger[orgnummer]?.contains(OPPRETT_NL_REALASJON_RESOURCE) != true)
                throw BadRequestException("Bruker har ikke tilgang til organisasjon $orgnummer") // Replace with ForbiddenException
        } catch (e: ResponseException) {
            throw InternalServerErrorException("Feil ved henting av altinn-tilganger")
        }
    }

    companion object {
        const val OPPRETT_NL_REALASJON_RESOURCE = "4596:1" // Access resource in Altinn2 to access NL relasjon
        private val logger = logger()
    }
}
