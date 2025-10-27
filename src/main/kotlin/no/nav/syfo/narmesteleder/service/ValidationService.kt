package no.nav.syfo.narmesteleder.service

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.api.v1.EmployeeLeaderConnection
import no.nav.syfo.narmesteleder.api.v1.EmployeeLeaderConnectionDiscontinued
import no.nav.syfo.narmesteleder.api.v1.domain.NarmestelederAktorer
import no.nav.syfo.narmesteleder.domain.NlBehovRead
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

    suspend fun validateNarmesteleder(
        employeeLeaderConnection: EmployeeLeaderConnection,
        principal: Principal,
    ): NarmestelederAktorer {
        try {
            val innsenderOrgNumber = validateAltTilgang(principal, employeeLeaderConnection.orgnumber)
            val sykmeldt = pdlService.getPersonOrThrowApiError(employeeLeaderConnection.employeeIdentificationNumber)
            val leder = pdlService.getPersonOrThrowApiError(employeeLeaderConnection.leader.nationalIdentificationNumber)
            val nlArbeidsforhold = aaregService.findOrgNumbersByPersonIdent(leder.nationalIdentificationNumber)
                .filter { it.key == employeeLeaderConnection.orgnumber }
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.nationalIdentificationNumber)
                    .filter { it.key == employeeLeaderConnection.orgnumber }
            validataActiveSykmelding(sykmeldt.nationalIdentificationNumber, employeeLeaderConnection.orgnumber)
            validateNarmesteLeder(
                orgNumberInRequest = employeeLeaderConnection.orgnumber,
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                narmesteLederOrgNumbers = nlArbeidsforhold,
                innsenderOrgNumber = innsenderOrgNumber
            )
            return NarmestelederAktorer(
                employee = sykmeldt,
                leader = leder,
            )
        } catch (e: ValidateNarmesteLederException) {
            logger.error("Validering av arbeidsforhold feilet {}", e.message)
            throw ApiErrorException.BadRequestException(
                "Error validating employment condition for the given organization number"
            )
        } catch (smExc: ValidateActiveSykmeldingException) {
            logger.error(
                "No active sick leave in organization number ${employeeLeaderConnection.orgnumber}",
                smExc.message
            )
            throw ApiErrorException.BadRequestException(
                smExc.message ?: "No active sick leave found for the given organization number"
            )
        }
    }

    internal suspend fun validataActiveSykmelding(fnr: String, orgnummer: String): Boolean =
        if (!dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer)) {
            throw ValidateActiveSykmeldingException("No active sick leave found for the given organization number")
        } else true

    suspend fun validateNarmestelederAvkreft(
        employeeLeaderConnectionDiscontinued: EmployeeLeaderConnectionDiscontinued,
        principal: Principal,
    ): Person {
        try {
            val innsenderOrgNumber = validateAltTilgang(principal, employeeLeaderConnectionDiscontinued.orgnumber)
            val sykmeldt = pdlService.getPersonOrThrowApiError(employeeLeaderConnectionDiscontinued.employeeIdentificationNumber)
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.nationalIdentificationNumber)
            validateNarmesteLederAvkreft(
                orgNumberInRequest = employeeLeaderConnectionDiscontinued.orgnumber,
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                innsenderOrgNumber = innsenderOrgNumber
            )
            return sykmeldt

        } catch (e: ValidateNarmesteLederException) {
            logger.error("Validering av arbeidsforhold feilet {}", e.message)
            throw ApiErrorException.BadRequestException("Error when validating persons")
        }
    }

    private suspend fun validateAltTilgang(principal: Principal, orgNumber: String): String? {
        return when (principal) {
            is BrukerPrincipal -> {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    principal,
                    orgNumber
                )
                null
            }

            is OrganisasjonPrincipal -> {
                maskinportenIdToOrgnumber(principal.ident)
            }
        }
    }

    suspend fun validateGetNlBehov(principal: Principal, nlBehovRead: NlBehovRead) {
        val sykemeldtOrgs = setOf(nlBehovRead.orgnummer, nlBehovRead.hovedenhetOrgnummer)
        val innsenderOrgNumber = validateAltTilgang(principal, nlBehovRead.orgnummer)

        if (principal is OrganisasjonPrincipal) {
            nlrequire(
                sykemeldtOrgs.contains(innsenderOrgNumber)
            ) { "Innsender is not employed in the same organization as sykemeld" }
        }
    }
}
