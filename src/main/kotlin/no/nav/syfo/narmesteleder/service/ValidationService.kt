package no.nav.syfo.narmesteleder.service

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.Organization
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.util.logger

class ValidationService(
    val pdlService: PdlService,
    val aaregService: AaregService,
    val altinnTilgangerService: no.nav.syfo.altinntilganger.AltinnTilgangerService,
    val dinesykmeldteService: DinesykmeldteService,
) {
    companion object {
        val logger = logger()
    }

    suspend fun validateLinemanager(
        linemanager: Linemanager,
        principal: Principal,
    ): LinemanagerActors {
        try {
            val innsenderOrgNumber = if (principal is UserPrincipal) {
                validateAltinnTilgang(principal, linemanager.orgnumber)
                null
            } else {
                maskinportenIdToOrgnumber(principal.ident)
            }
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
                innsenderOrgNumber = innsenderOrgNumber
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

    fun validateGetNlBehov(
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
                nlrequire(
                    sykemeldtOrgs.contains(maskinportenIdToOrgnumber(principal.ident))
                ) { "Person making the request is not employed in the same organization as employee on sick leave" }
            }
        }
    }


    suspend fun validateLinemanagerRevoke(
        linemanagerRevoke: LinemanagerRevoke,
        principal: Principal,
    ): Person {
        try {
            val innsenderOrgNumber = if (principal is UserPrincipal) {
                validateAltinnTilgang(principal, linemanagerRevoke.orgnumber)
                null
            } else {
                maskinportenIdToOrgnumber(principal.ident)
            }
            val sykmeldt = pdlService.getPersonOrThrowApiError(linemanagerRevoke.employeeIdentificationNumber)
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.nationalIdentificationNumber)
            validateNarmesteLederAvkreft(
                orgNumberInRequest = linemanagerRevoke.orgnumber,
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                innsenderOrgNumber = innsenderOrgNumber
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

    private suspend fun validateAltinnTilgang(principal: UserPrincipal, orgNumber: String): Organization? {
        val altinnTilgang = altinnTilgangerService.validateTilgangToOrganization(
            principal,
            orgNumber
        )
        return Organization(
            altinnTilgang.orgnr,
            altinnTilgang.navn
        )
    }

}
