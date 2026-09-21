package no.nav.syfo.altinntilganger

import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.IAltinnTilgangerClient
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamFailureStage
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.logging.applicationLogger

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
            if (!hasAltinn3Resource) {
                throw ApiErrorException.ForbiddenException(
                    errorMessage = "User lacks access to required Altinn resource for organization: $orgnummer",
                    type = ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                )
            }
            COUNT_HAS_ALTINN3_RESOURCE.increment()
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
        val origin = cause.cause ?: cause
        logger.event(
            operation.failureEvent,
            AltinnTilgangerFailure(
                errorCode = cause.errorCode(),
                exceptionType = cause.upstreamExceptionType,
                causeType = origin.safeCauseType(),
                failureStage = cause.failureStage,
                upstreamStatus = cause.upstreamStatus,
            ),
            SanitizedUpstreamFailure(origin.stackTrace),
        )
    }

    private fun logAltinnTilgangerLookupFailure(
        errorCode: AltinnTilgangerErrorCode,
        operation: AltinnTilgangerOperation,
    ) {
        logger.event(operation.failureEvent, AltinnTilgangerFailure(errorCode))
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

private class SanitizedUpstreamFailure(originStackTrace: Array<StackTraceElement>) : RuntimeException() {
    init {
        stackTrace = originStackTrace
    }
}

private val SAFE_CAUSE_TYPE = Regex("^[A-Za-z][A-Za-z0-9]{0,79}$")

private fun Throwable.safeCauseType(): String = javaClass.simpleName.takeIf(SAFE_CAUSE_TYPE::matches) ?: "Throwable"
