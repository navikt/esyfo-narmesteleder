package no.nav.syfo.altinntilganger

import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.IAltinnTilgangerClient
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.util.logger

class AltinnAccessService(
    val altinnTilgangerClient: IAltinnTilgangerClient,
) {
    suspend fun validateTilgangToOrganization(
        userPrincipal: UserPrincipal,
        orgnummer: String,
    ): AltinnTilgang {
        val altinnTilgang = getAltinnTilgangForOrgnr(userPrincipal, orgnummer)
        validateTilgangToOrganization(altinnTilgang, orgnummer)
        return altinnTilgang!!
    }

    fun validateTilgangToOrganization(
        altinnTilgang: AltinnTilgang?,
        orgnummer: String
    ) {
        altinnTilgang?.let {
            val hasAltinn3Resource = it.altinn3Tilganger.contains(OPPGI_NARMESTELEDER_RESOURCE)
            val hasAltinn2Resource = it.altinn2Tilganger.contains(OPPRETT_NL_REALASJON_RESOURCE)
            when {
                hasAltinn3Resource -> {
                    COUNT_HAS_ALTINN3_RESOURCE.increment()
                }

                hasAltinn2Resource && !hasAltinn3Resource -> {
                    COUNT_HAS_ALTINN2_AND_NOT_ALTIN3_RESOURCE.increment()
                    // We might add logging of the org numbers that only has altinn2 access here
                }
            }
            if (!(hasAltinn3Resource || hasAltinn2Resource)) {
                throw ApiErrorException.ForbiddenException(
                    errorMessage = "User lacks access to required Altinn resource for organization: $orgnummer",
                    type = ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                )
            }
        } ?: throw ApiErrorException.ForbiddenException(
            errorMessage = "User lacks access to organization: $orgnummer",
            type = ErrorType.MISSING_ORG_ACCESS
        )
    }

    suspend fun getAltinnTilgangForOrgnr(
        userPrincipal: UserPrincipal,
        orgnummer: String,
    ): AltinnTilgang? {
        try {
            return altinnTilgangerClient.fetchAltinnTilganger(userPrincipal)?.hierarki?.findByOrgnr(orgnummer)
        } catch (e: UpstreamRequestException) {
            logger.error("Error when fetching altinn resources available to owner to authorization token", e)
            throw ApiErrorException.InternalServerErrorException("An error occurred when fetching altinn resources for users authorization token")
        }
    }

    suspend fun getFilteredOrganizations(userPrincipal: UserPrincipal): List<AccessibleOrganization> {
        try {
            val response = altinnTilgangerClient.fetchAltinnTilganger(userPrincipal)
                ?: return emptyList()
            if (response.isError == true) {
                logger.warn("Altinn tilganger proxy reported error - returning empty list")
                return emptyList()
            }
            return response.hierarki.filterToOrganizations()
        } catch (e: UpstreamRequestException) {
            logger.error("Error when fetching altinn tilganger for organisasjon filtering", e)
            throw ApiErrorException.InternalServerErrorException("An error occurred when fetching altinn tilganger")
        }
    }

    private fun List<AltinnTilgang>.filterToOrganizations(): List<AccessibleOrganization> = mapNotNull { it.filterAccess() }

    private fun AltinnTilgang.filterAccess(): AccessibleOrganization? {
        val filteredSubOrganizations = underenheter.filterToOrganizations()
        val hasAccess = hasNarmestelederTilgang()

        return if (hasAccess && filteredSubOrganizations.isNotEmpty()) {
            AccessibleOrganization(
                orgNumber = orgnr,
                name = navn,
                subOrganizations = filteredSubOrganizations,
            )
        } else {
            null
        }
    }

    private fun AltinnTilgang.hasNarmestelederTilgang(): Boolean = altinn3Tilganger.contains(OPPGI_NARMESTELEDER_RESOURCE) ||
        altinn2Tilganger.contains(OPPRETT_NL_REALASJON_RESOURCE)

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
        const val OPPGI_NARMESTELEDER_RESOURCE =
            "nav_syfo_oppgi-narmesteleder" // Access resource in Altinn3 to access NL relasjon
        const val OPPRETT_NL_REALASJON_RESOURCE = "4596:1" // Access resource in Altinn2 to access NL relasjon
        private val logger = logger()
    }
}
