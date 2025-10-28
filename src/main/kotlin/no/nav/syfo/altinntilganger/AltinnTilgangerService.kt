package no.nav.syfo.altinntilganger

import no.nav.syfo.altinntilganger.client.IAltinnTilgangerClient
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.util.logger

class AltinnTilgangerService(
    val altinnTilgangerClient: IAltinnTilgangerClient,
) {
    suspend fun validateTilgangToOrganization(
        userPrincipal: UserPrincipal,
        orgnummer: String,
    ) {
        try {
            val tilganger = altinnTilgangerClient.hentTilganger(userPrincipal)
            if (tilganger?.orgNrTilTilganger[orgnummer]?.contains(OPPGI_NARMESTELEDER_RESOURCE) != true)
                throw ApiErrorException.ForbiddenException("User lacks access to requires altinn resource for organization: $orgnummer")
        } catch (e: UpstreamRequestException) {
            logger.error("Error when fetching altinn resources available to owner to authorization token", e)
            throw ApiErrorException.InternalServerErrorException("An error occurred when fetching altinn resources for users authorization token")
        }
    }

    companion object {
        const val OPPGI_NARMESTELEDER_RESOURCE = "nav_syfo_oppgi-narmesteleder" // Access resource in Altinn2 to access NL relasjon
        private val logger = logger()
    }
}
