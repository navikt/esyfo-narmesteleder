package no.nav.syfo.narmesteleder.service

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.Arbeidsforhold
import no.nav.syfo.aareg.getForOrgnummer
import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.ereg.EregService
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.util.logger

class ValidationService(
    val pdlService: PdlService,
    val aaregService: AaregService,
    val altinnTilgangerService: AltinnTilgangerService,
    val dinesykmeldteService: DinesykmeldteService,
    val pdpService: PdpService,
    val eregService: EregService,
) {
    companion object {
        val logger = logger()
    }

    suspend fun validateLinemanager(
        linemanager: Linemanager,
        principal: Principal,
        validateEmployeeLastName: Boolean = true,
    ): LinemanagerActors {
        val sykemeldtArbeidsforhold =
            aaregService.findArbeidsforholdByPersonIdent(linemanager.employeeIdentificationNumber)
        validatePrincipalAccessToOrgnumber(
            principal,
            linemanager.orgNumber,
            sykemeldtArbeidsforhold.getForOrgnummer(linemanager.orgNumber)
        )
        validateActiveSickLeave(linemanager.employeeIdentificationNumber, linemanager.orgNumber)
        val sykmeldt = pdlService.getPersonOrThrowApiError(linemanager.employeeIdentificationNumber)
        val leder = pdlService.getPersonOrThrowApiError(linemanager.manager.nationalIdentificationNumber)

        val nlArbeidsforhold = aaregService.findArbeidsforholdByPersonIdent(leder.nationalIdentificationNumber)
        validateLinemanagerLastName(leder, linemanager)
        if (validateEmployeeLastName) validateEmployeeLastName(sykmeldt, linemanager)

        validateSmAndNlArbeidsforhold(
            sykmeldtArbeidsforhold = sykemeldtArbeidsforhold,
            narmesteLederArbeidsforhold = nlArbeidsforhold,
            orgNumberInRequest = linemanager.orgNumber
        )
        return LinemanagerActors(
            employee = sykmeldt,
            manager = leder,
        )
    }

    suspend fun validateLinemanagerRevoke(
        linemanagerRevoke: LinemanagerRevoke,
        principal: Principal,
    ): Person {
        val abeidsforhold =
            aaregService.findArbeidsforholdByPersonIdent(linemanagerRevoke.employeeIdentificationNumber)
        validatePrincipalAccessToOrgnumber(
            principal,
            linemanagerRevoke.orgNumber,
            abeidsforhold.getForOrgnummer(linemanagerRevoke.orgNumber)
        )
        val sykmeldt = pdlService.getPersonOrThrowApiError(linemanagerRevoke.employeeIdentificationNumber)
        validateNarmesteLederAvkreft(
            orgNumberInRequest = linemanagerRevoke.orgNumber,
            sykmeltArbeidsforhold = abeidsforhold,
        )
        validateEmployeeLastName(sykmeldt, linemanagerRevoke)

        return sykmeldt
    }

    /**
     * Validated if the principal from authorization token, has access to the organization related to the request.
     * For system principals, this is done by checking if the organization number in the token matches the organization number in the request,
     * or if the organization number in the request is part of the hierarchy of organizations related to the system principal.
     *
     * For user principals, this is done by checking if the user has access to the organization number in the request
     * through Altinn by checking with service AltinnTilganger.
     *
     * It returns the name of the organization if the principal is a user principal,
     * and null if the principal is a system principal,
     * as this information is not available in the token, and we do not fetch it from Ereg.
     */
    suspend fun validatePrincipalAccessToOrgnumber(
        principal: Principal,
        orgNumber: String,
        arbeidsforhold: Arbeidsforhold? = null
    ): String? = when (principal) {
        is SystemPrincipal -> {
            val orgnumbersToValidate = when {
                principal.getSystemUserOrgNumber() == orgNumber -> setOf(orgNumber)
                arbeidsforhold?.opplysningspliktigOrgnummer != null -> arbeidsforhold.toOrgnummerList().toSet()
                else -> {
                    logger.info("System principal does not have direct access to the organization number in the request, checking Ereg for org hierarchy")
                    eregService.getOrganization(orgNumber).aggregerOrgnummereFraHierarki()
                }
            }
            validateSystemPrincipal(orgnumbersToValidate, principal)
            null
        }

        is UserPrincipal -> {
            val altinnTilgang = altinnTilgangerService.validateTilgangToOrganization(
                userPrincipal = principal,
                orgnummer = orgNumber
            )
            altinnTilgang.navn.trim()
        }
    }

    private suspend fun validateSystemPrincipal(validOrgnumbers: Set<String>, principal: SystemPrincipal) {
        if (!validOrgnumbers.contains(principal.getSystemUserOrgNumber())) {
            throw ApiErrorException.ForbiddenException(
                errorMessage = "System ${principal.systemUserId} is not registered in the same organization as the context of the request",
                type = ErrorType.MISSING_ORG_ACCESS
            )
        }
        val hasAccess = pdpService.hasAccessToResource(
            System(principal.systemUserId),
            setOf(principal.getSystemUserOrgNumber(), principal.getSystemOwnerOrgNumber()),
            OPPGI_NARMESTELEDER_RESOURCE
        )
        if (!hasAccess) {
            throw ApiErrorException.ForbiddenException(
                errorMessage = "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource",
                type = ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
            )
        }
    }

    private suspend fun validateActiveSickLeave(fnr: String, orgnummer: String) {
        if (!dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer)) {
            val message = "No active sick leave found for the given organization number: $orgnummer"
            logger.warn(message)
            throw ApiErrorException.BadRequestException(
                errorMessage = message,
                type = ErrorType.NO_ACTIVE_SICK_LEAVE
            )
        }
    }
}
