package no.nav.syfo.narmesteleder.service.validators

import no.nav.syfo.aareg.Arbeidsforhold
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
import no.nav.syfo.util.logger

class PrincipalAccessValidator(
    private val altinnTilgangerService: AltinnTilgangerService,
    private val pdpService: PdpService,
    private val eregService: EregService,
) {
    companion object {
        val logger = logger()
    }

    suspend fun validatePrincipalAccessToOrgnumber(
        principal: Principal,
        orgNumber: String,
        arbeidsforhold: Arbeidsforhold? = null,
    ): String? = when (principal) {
        is SystemPrincipal -> {
            val orgnumbersToValidate = when {
                principal.getSystemUserOrgNumber() == orgNumber -> setOf(orgNumber)
                arbeidsforhold?.opplysningspliktigOrgnummer != null -> arbeidsforhold.toOrgnummerList().toSet()
                else -> {
                    logger.info(
                        "System principal does not have direct access to the organization number in the request, checking Ereg for org hierarchy",
                    )
                    eregService.getOrganization(orgNumber).aggregerOrgnummereFraHierarki()
                }
            }
            validateSystemPrincipal(orgnumbersToValidate, principal)
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
        validOrgnumbers: Set<String>,
        principal: SystemPrincipal,
    ) {
        if (!validOrgnumbers.contains(principal.getSystemUserOrgNumber())) {
            throw ApiErrorException.ForbiddenException(
                errorMessage = "System ${principal.systemUserId} is not registered in the same organization as the context of the request",
                type = ErrorType.MISSING_ORG_ACCESS,
            )
        }
        val hasAccess = pdpService.hasAccessToResource(
            System(principal.systemUserId),
            setOf(principal.getSystemUserOrgNumber(), principal.getSystemOwnerOrgNumber()),
            OPPGI_NARMESTELEDER_RESOURCE,
        )
        if (!hasAccess) {
            throw ApiErrorException.ForbiddenException(
                errorMessage = "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource",
                type = ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
            )
        }
    }
}
