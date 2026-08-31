package no.nav.syfo.altinntilganger

import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.IAltinnTilgangerClient
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import org.slf4j.LoggerFactory

class AltinnTilgangerService(
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
            logAltinnAccessLookupFailure(e, LOOKUP_ORGANIZATION_ACCESS_OPERATION)
            throw ApiErrorException.InternalServerErrorException(
                errorMessage = "An error occurred when fetching altinn resources for users authorization token",
                cause = e,
                isAlreadyLogged = true,
            )
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
            logAltinnAccessLookupFailure(e, LIST_ACCESSIBLE_ORGANIZATIONS_OPERATION)
            throw ApiErrorException.InternalServerErrorException(
                errorMessage = "An error occurred when fetching altinn tilganger",
                cause = e,
                isAlreadyLogged = true,
            )
        }
    }

    private fun logAltinnAccessLookupFailure(cause: UpstreamRequestException, operation: String) {
        logger.atError()
            .addKeyValue("event_type", ALTINN_ACCESS_LOOKUP_FAILED_EVENT_TYPE)
            .addKeyValue("error_code", cause.errorCode())
            .addKeyValue("operation", operation)
            .addKeyValue("exception_type", cause.safeUpstreamExceptionType())
            .setCause(cause)
            .log("Altinn access lookup failed")
    }

    private fun List<AltinnTilgang>.filterToOrganizations(): List<AccessibleOrganization> = mapNotNull { it.filterAccess() }

    private fun AltinnTilgang.filterAccess(): AccessibleOrganization? {
        val filteredSubOrganizations = underenheter.filterToOrganizations()
        val hasAccess = hasNarmestelederTilgang()

        return if (hasAccess || filteredSubOrganizations.isNotEmpty()) {
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
        internal const val ALTINN_ACCESS_LOOKUP_FAILED_EVENT_TYPE = "altinn_access_lookup_failed"
        internal const val ALTINN_ACCESS_UPSTREAM_4XX_ERROR_CODE = "ALTINN_ACCESS_UPSTREAM_4XX"
        internal const val ALTINN_ACCESS_UPSTREAM_5XX_ERROR_CODE = "ALTINN_ACCESS_UPSTREAM_5XX"
        internal const val ALTINN_ACCESS_UPSTREAM_FAILURE_ERROR_CODE = "ALTINN_ACCESS_UPSTREAM_FAILURE"
        internal const val LOOKUP_ORGANIZATION_ACCESS_OPERATION = "lookup_organization_access"
        internal const val LIST_ACCESSIBLE_ORGANIZATIONS_OPERATION = "list_accessible_organizations"

        const val OPPGI_NARMESTELEDER_RESOURCE =
            "nav_syfo_oppgi-narmesteleder" // Access resource in Altinn3 to access NL relasjon
        const val OPPRETT_NL_REALASJON_RESOURCE = "4596:1" // Access resource in Altinn2 to access NL relasjon
        private val logger = LoggerFactory.getLogger(AltinnTilgangerService::class.java)
    }
}

private fun UpstreamRequestException.errorCode(): String = when (upstreamStatus) {
    in 400..499 -> AltinnTilgangerService.ALTINN_ACCESS_UPSTREAM_4XX_ERROR_CODE
    in 500..599 -> AltinnTilgangerService.ALTINN_ACCESS_UPSTREAM_5XX_ERROR_CODE
    else -> AltinnTilgangerService.ALTINN_ACCESS_UPSTREAM_FAILURE_ERROR_CODE
}

private fun UpstreamRequestException.safeUpstreamExceptionType(): String = upstreamExceptionType
    ?.takeIf { safeExceptionTypePattern.matches(it) }
    ?: UpstreamRequestException::class.simpleName!!

private val safeExceptionTypePattern = Regex("^[A-Za-z][A-Za-z0-9_.:$]{0,159}$")
