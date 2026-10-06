package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamFailureStage
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.failureDiagnostics
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganization
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganizationsLookup
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsResult
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class AltinnTilgangerService(
    val altinnTilgangerClient: AltinnTilgangerClient,
) : AccessibleOrganizationsLookup {
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

    override suspend fun find(subject: OrganizationAccessSubject.PersonnelManager): ListAccessibleOrganizationsResult {
        try {
            val userPrincipal = UserPrincipal(subject.personIdent.value, subject.accessToken.value())
            val response = altinnTilgangerClient.fetchAltinnTilganger(userPrincipal)
                ?: return ListAccessibleOrganizationsResult.Listed(emptyList())
            if (response.isError == true) {
                logAltinnTilgangerLookupFailure(
                    errorCode = AltinnTilgangerErrorCode.ERROR_RESPONSE,
                    operation = AltinnTilgangerOperation.LIST_ACCESSIBLE_ORGANIZATIONS,
                )
                return ListAccessibleOrganizationsResult.Listed(emptyList())
            }
            return ListAccessibleOrganizationsResult.Listed(response.hierarki.filterToOrganizations())
        } catch (e: UpstreamRequestException) {
            logAltinnTilgangerLookupFailure(e, AltinnTilgangerOperation.LIST_ACCESSIBLE_ORGANIZATIONS)
            return ListAccessibleOrganizationsResult.Unavailable
        }
    }

    companion object {
        private val logger = applicationLogger(AltinnTilgangerService::class.java)
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
                organizationNumber = orgnr,
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
