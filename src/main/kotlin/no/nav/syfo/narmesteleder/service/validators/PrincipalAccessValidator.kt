package no.nav.syfo.narmesteleder.service.validators

import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ereg.EregService
import no.nav.syfo.logging.applicationLogger

class PrincipalAccessValidator(
    private val altinnTilgangerService: AltinnTilgangerService,
    private val pdpService: PdpService,
    private val eregService: EregService,
) {
    companion object {
        private val logger = applicationLogger(javaClass)
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
        val directDecision = pdpService.accessDecisionForResource(
            user = System(principal.systemUserId),
            orgNumberSet = setOf(requestedOrgnumber.trim()),
            resource = OPPGI_NARMESTELEDER_RESOURCE,
        )
        if (directDecision == Decision.Permit) {
            return
        }

        val fallbackDecision = accessDecisionThroughPrincipalOrgnumber(requestedOrgnumber, principal)
        if (fallbackDecision == Decision.Permit) {
            return
        }

        logger.event(systemUserAccessRejected, SystemUserAccessRejection(directDecision, fallbackDecision))

        throw ApiErrorException.ForbiddenException(
            errorMessage = "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource",
            type = ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
            isAlreadyLogged = true,
        )
    }

    private suspend fun accessDecisionThroughPrincipalOrgnumber(
        requestedOrgnumber: String,
        principal: SystemPrincipal,
    ): Decision? {
        val organisasjon = eregService.getOrganization(requestedOrgnumber)
        val orgnummerList = organisasjon.aggregerOrgnummereFraHierarki()
        val matchesPrincipal = orgnummerList.contains(principal.getSystemUserOrgNumber())
        return if (matchesPrincipal) {
            pdpService.accessDecisionForResource(
                user = System(principal.systemUserId),
                orgNumberSet = setOf(principal.getSystemUserOrgNumber()),
                resource = OPPGI_NARMESTELEDER_RESOURCE,
            )
        } else {
            null
        }
    }
}
