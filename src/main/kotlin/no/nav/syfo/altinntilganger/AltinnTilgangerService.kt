package no.nav.syfo.altinntilganger

import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.AltinnTilgangerClient
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamFailureStage
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.failureDiagnostics

class AltinnTilgangerService(
    val altinnTilgangerClient: AltinnTilgangerClient,
) {
    suspend fun getAuthorizedAltinnTilgang(
        userPrincipal: UserPrincipal,
        orgnummer: String,
    ): AltinnTilgang = getAltinnTilgangForOrgnr(userPrincipal, orgnummer)
        .requireNarmestelederAccess(orgnummer)

    private fun AltinnTilgang?.requireNarmestelederAccess(
        orgnummer: String
    ): AltinnTilgang {
        val altinnTilgang = this ?: throw ApiErrorException.ForbiddenException(
            errorMessage = "User lacks access to organization: $orgnummer",
            type = ErrorType.MISSING_ORG_ACCESS,
        )
        if (!altinnTilgang.hasNarmestelederTilgang()) {
            throw ApiErrorException.ForbiddenException(
                errorMessage = "User lacks access to required Altinn resource for organization: $orgnummer",
                type = ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
            )
        }

        COUNT_HAS_ALTINN3_RESOURCE.increment()
        return altinnTilgang
    }

    suspend fun getAltinnTilgangForOrgnr(
        userPrincipal: UserPrincipal,
        orgnummer: String,
    ): AltinnTilgang? {
        try {
            val response = altinnTilgangerClient.fetchAltinnTilganger(userPrincipal)
                ?: return null
            return response.hierarki.findByOrgnr(orgnummer)
        } catch (e: UpstreamRequestException) {
            logAltinnTilgangerLookupFailure(e, AltinnTilgangerOperation.LOOKUP_ORGANIZATION_ACCESS)
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
                logAltinnTilgangerLookupFailure(
                    errorCode = AltinnTilgangerErrorCode.ERROR_RESPONSE,
                    operation = AltinnTilgangerOperation.LIST_ACCESSIBLE_ORGANIZATIONS,
                )
                return emptyList()
            }
            return response.hierarki.filterToOrganizations()
        } catch (e: UpstreamRequestException) {
            logAltinnTilgangerLookupFailure(e, AltinnTilgangerOperation.LIST_ACCESSIBLE_ORGANIZATIONS)
            throw ApiErrorException.InternalServerErrorException(
                errorMessage = "An error occurred when fetching altinn tilganger",
                cause = e,
                isAlreadyLogged = true,
            )
        }
    }

    private fun logAltinnTilgangerLookupFailure(
        cause: UpstreamRequestException,
        operation: AltinnTilgangerOperation,
    ) {
        val origin = (cause.cause ?: cause).failureDiagnostics()
        logger.event(
            operation.failureEvent,
            AltinnTilgangerFailure(
                errorCode = cause.errorCode(),
                exceptionType = cause.upstreamExceptionType,
                causeType = origin.causeType,
                causeTypes = origin.causeTypes,
                failureKind = origin.failureKind,
                upstream = cause.upstream ?: "arbeidsgiver-altinn-tilganger",
                upstreamErrorCode = cause.upstreamErrorCode?.name,
                upstreamStatus = cause.upstreamStatus,
            ),
            origin.stack,
        )
    }

    private fun logAltinnTilgangerLookupFailure(
        errorCode: AltinnTilgangerErrorCode,
        operation: AltinnTilgangerOperation,
    ) {
        logger.event(operation.failureEvent, AltinnTilgangerFailure(errorCode, failureKind = "domain"))
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

    private fun AltinnTilgang.hasNarmestelederTilgang(): Boolean = altinn3Tilganger.contains(OPPGI_NARMESTELEDER_RESOURCE)

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
        private val logger = applicationLogger(AltinnTilgangerService::class.java)
    }
}

private fun UpstreamRequestException.errorCode(): AltinnTilgangerErrorCode = when {
    failureStage == UpstreamFailureStage.TOKEN_EXCHANGE -> AltinnTilgangerErrorCode.TOKEN_EXCHANGE_FAILED
    upstreamStatus in 300..399 -> AltinnTilgangerErrorCode.UPSTREAM_UNEXPECTED_REDIRECT
    upstreamStatus == 401 -> AltinnTilgangerErrorCode.UPSTREAM_UNAUTHORIZED
    upstreamStatus == 403 -> AltinnTilgangerErrorCode.UPSTREAM_FORBIDDEN
    upstreamStatus == 404 -> AltinnTilgangerErrorCode.UPSTREAM_NOT_FOUND
    upstreamStatus == 429 -> AltinnTilgangerErrorCode.UPSTREAM_RATE_LIMITED
    upstreamStatus in 400..499 -> AltinnTilgangerErrorCode.UPSTREAM_CLIENT_ERROR
    upstreamStatus in 500..599 -> AltinnTilgangerErrorCode.UPSTREAM_SERVER_ERROR
    failureStage == UpstreamFailureStage.RESPONSE -> AltinnTilgangerErrorCode.UPSTREAM_RESPONSE_FAILURE
    else -> AltinnTilgangerErrorCode.UPSTREAM_TRANSPORT_FAILURE
}
