package no.nav.syfo.narmesteleder.service.validators

import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.util.logger

class PrincipalAccessValidator(
    private val altinnTilgangerService: AltinnTilgangerService,
    private val pdpService: PdpService,
) {
    companion object {
        val logger = logger()
    }

    suspend fun validatePrincipalAccessToOrgnumber(
        principal: Principal,
        orgNumber: String,
    ): String? = when (principal) {
        is SystemPrincipal -> {
            validateSystemPrincipal(orgNumber, principal)
            null
        }

        is UserPrincipal -> {
            val altinnTilgang = altinnTilgangerService.validateTilgangToOrganization(
                userPrincipal = principal,
                orgnummer = orgNumber,
            )
            altinnTilgang.navn.trim()
        }
    }

    private suspend fun validateSystemPrincipal(
        requestedOrgnumber: String,
        principal: SystemPrincipal,
    ) {
        val hasAccess = pdpService.hasAccessToResource(
            user = System(principal.systemUserId),
            orgNumberSet = setOf(requestedOrgnumber.trim()),
            resource = OPPGI_NARMESTELEDER_RESOURCE,
        )
        if (!hasAccess) {
            throw ApiErrorException.ForbiddenException(
                errorMessage = "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource",
                type = ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
            )
        }
    }
}
