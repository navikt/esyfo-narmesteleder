package no.nav.syfo.narmesteleder.service

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.util.logger

class ValidationService(
    val pdlService: PdlService,
    val aaregService: AaregService,
    val altinnTilgangerService: AltinnTilgangerService,
    val dinesykmeldteService: DinesykmeldteService,
    val pdpService: PdpService
) {
    companion object {
        val logger = logger()
    }

    suspend fun validateLinemanager(
        linemanager: Linemanager,
        principal: Principal,
    ): LinemanagerActors {
        try {
            validateAltinnTilgang(principal, linemanager.orgnumber)
            val sykmeldt = pdlService.getPersonOrThrowApiError(linemanager.employeeIdentificationNumber)
            val leder = pdlService.getPersonOrThrowApiError(linemanager.manager.nationalIdentificationNumber)
            val nlArbeidsforhold = aaregService.findOrgNumbersByPersonIdent(leder.nationalIdentificationNumber)
                .filter { it.key == linemanager.orgnumber }
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.nationalIdentificationNumber)
                    .filter { it.key == linemanager.orgnumber }
            validataActiveSickLeave(sykmeldt.nationalIdentificationNumber, linemanager.orgnumber)
            validateNarmesteLeder(
                orgNumberInRequest = linemanager.orgnumber,
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                narmesteLederOrgNumbers = nlArbeidsforhold,
                organisasjonPrincipal = principal as? OrganisasjonPrincipal,
            )
            return LinemanagerActors(
                employee = sykmeldt,
                manager = leder,
            )
        } catch (e: ValidateNarmesteLederException) {
            logger.error("Validation of active employment status failed {}", e.message)
            throw ApiErrorException.BadRequestException(
                "Error validating employment status for the given organization number"
            )
        } catch (smExc: ValidateActiveSykmeldingException) {
            logger.error(
                "No active sick leave in organization number ${linemanager.orgnumber}",
                smExc.message
            )
            throw ApiErrorException.BadRequestException(
                smExc.message ?: "No active sick leave found for the given organization number"
            )
        }
    }

    suspend fun validateGetNlBehov(
        principal: Principal,
        linemanagerRead: LinemanagerRequirementRead,
        altinnTilgang: AltinnTilgang?
    ) {
        val sykemeldtOrgs = setOf(linemanagerRead.orgnumber, linemanagerRead.mainOrgnumber)
        when (principal) {
            is UserPrincipal -> {
                altinnTilgangerService.validateTilgangToOrganization(altinnTilgang, linemanagerRead.orgnumber)
            }

            is OrganisasjonPrincipal -> {
                val hasAccess = pdpService.hasAccessToResource(
                    System(principal.systemUserId),
                    setOf(principal.getSystemUserOrgNumber(), principal.getSystemOwnerOrgNumber()),
                    OPPGI_NARMESTELEDER_RESOURCE
                )
                if (hasAccess) {
                    nlrequire(
                        sykemeldtOrgs.contains(principal.getSystemUserOrgNumber())
                    ) { "Person making the request is not employed in the same organization as employee on sick leave" }
                } else {
                    throw ApiErrorException.ForbiddenException(
                        "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource"
                    )
                }
            }
        }
    }

    suspend fun validateLinemanagerRevoke(
        linemanagerRevoke: LinemanagerRevoke,
        principal: Principal,
    ): Person {
        try {
            validateAltinnTilgang(principal, linemanagerRevoke.orgnumber)
            val sykmeldt = pdlService.getPersonOrThrowApiError(linemanagerRevoke.employeeIdentificationNumber)
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.nationalIdentificationNumber)
            validateNarmesteLederAvkreft(
                orgNumberInRequest = linemanagerRevoke.orgnumber,
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                organisasjonPrincipal = principal as? OrganisasjonPrincipal,
            )
            return sykmeldt

        } catch (e: ValidateNarmesteLederException) {
            logger.error("Validation of employment situation failed {}", e.message)
            throw ApiErrorException.BadRequestException("Error when validating persons")
        }
    }

    private suspend fun validataActiveSickLeave(fnr: String, orgnummer: String) {
        if (!dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer)) {
            throw ValidateActiveSykmeldingException("No active sick leave found for the given organization number")
        }
    }

    private suspend fun validateAltinnTilgang(principal: Principal, orgNumber: String) {
        when (principal) {
            is UserPrincipal -> altinnTilgangerService.validateTilgangToOrganization(
                principal,
                orgNumber
            )
            is OrganisasjonPrincipal -> {
                val hasAccess = pdpService.hasAccessToResource(
                    System(principal.systemUserId),
                    setOf(principal.getSystemUserOrgNumber(), principal.getSystemOwnerOrgNumber()),
                    OPPGI_NARMESTELEDER_RESOURCE
                )
                if (!hasAccess) {
                    throw ApiErrorException.ForbiddenException(
                        "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource"
                    )
                }
            }
        }
    }
}
