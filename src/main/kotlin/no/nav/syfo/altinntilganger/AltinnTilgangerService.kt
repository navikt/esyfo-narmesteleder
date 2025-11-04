package no.nav.syfo.altinntilganger

import no.nav.syfo.altinntilganger.client.AltinnTilgang
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
    ): AltinnTilgang? {
        try {
            val tilganger = altinnTilgangerClient.fetchAltinnTilganger(userPrincipal)
            val org = tilganger?.hierarki?.findByOrgnr(orgnummer)
            if (org == null)
                throw ApiErrorException.ForbiddenException("User lacks access to organization: $orgnummer")
            if (org.altinn3Tilganger.contains(OPPGI_NARMESTELEDER_RESOURCE) != true) {
                throw ApiErrorException.ForbiddenException("User lacks access to required altinn3 resource for organization: $orgnummer")
            }
            return org
        } catch (e: UpstreamRequestException) {
            logger.error("Error when fetching altinn resources available to owner to authorization token", e)
            throw ApiErrorException.InternalServerErrorException("An error occurred when fetching altinn resources for users authorization token")
        }
    }

    private fun List<AltinnTilgang>.findByOrgnr(targetOrgnr: String): AltinnTilgang? {
        for (tilgang in this) {
            if (tilgang.orgnr == targetOrgnr) {
                return tilgang
            }
            tilgang.underenheter.findByOrgnr(targetOrgnr)?.let { return it }
        }
        return null
    }

    companion object {
        const val OPPGI_NARMESTELEDER_RESOURCE = "nav_syfo_oppgi-narmesteleder" // Access resource in Altinn2 to access NL relasjon
        private val logger = logger()
    }
}
