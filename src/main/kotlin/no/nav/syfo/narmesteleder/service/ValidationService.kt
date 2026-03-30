package no.nav.syfo.narmesteleder.service

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.Arbeidsforhold
import no.nav.syfo.aareg.getForOrgnummer
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.service.validators.ArbeidsforholdValidator
import no.nav.syfo.narmesteleder.service.validators.NameValidator
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person

class ValidationService(
    private val pdlService: PdlService,
    private val aaregService: AaregService,
    private val principalAccessValidator: PrincipalAccessValidator,
    private val sickLeaveValidator: SickLeaveValidator,
) {
    suspend fun validateLinemanager(
        linemanager: Linemanager,
        principal: Principal,
        validateEmployeeLastName: Boolean = true,
    ): LinemanagerActors {
        val sykmeldtArbeidsforhold =
            aaregService.findArbeidsforholdByPersonIdent(linemanager.employeeIdentificationNumber)
        principalAccessValidator.validatePrincipalAccessToOrgnumber(
            principal,
            linemanager.orgNumber,
            sykmeldtArbeidsforhold.getForOrgnummer(linemanager.orgNumber),
        )
        sickLeaveValidator.validateActiveSickLeave(linemanager.employeeIdentificationNumber, linemanager.orgNumber)

        val nlArbeidsforhold =
            aaregService.findArbeidsforholdByPersonIdent(linemanager.manager.nationalIdentificationNumber)
        ArbeidsforholdValidator.validateSmAndNlArbeidsforhold(
            sykmeldtArbeidsforhold = sykmeldtArbeidsforhold,
            narmesteLederArbeidsforhold = nlArbeidsforhold,
            orgNumberInRequest = linemanager.orgNumber,
        )

        val sykmeldt = pdlService.getPersonOrThrowApiError(linemanager.employeeIdentificationNumber)
        val leder = pdlService.getPersonOrThrowApiError(linemanager.manager.nationalIdentificationNumber)
        NameValidator.validateLinemanagerLastName(leder, linemanager)
        if (validateEmployeeLastName) {
            NameValidator.validateEmployeeLastName(sykmeldt, linemanager)
        }

        return LinemanagerActors(
            employee = sykmeldt,
            manager = leder,
        )
    }

    suspend fun validateLinemanagerRevoke(
        linemanagerRevoke: LinemanagerRevoke,
        principal: Principal,
    ): Person {
        val arbeidsforhold =
            aaregService.findArbeidsforholdByPersonIdent(linemanagerRevoke.employeeIdentificationNumber)
        principalAccessValidator.validatePrincipalAccessToOrgnumber(
            principal,
            linemanagerRevoke.orgNumber,
            arbeidsforhold.getForOrgnummer(linemanagerRevoke.orgNumber),
        )
        ArbeidsforholdValidator.validateNarmesteLederAvkreft(
            orgNumberInRequest = linemanagerRevoke.orgNumber,
            sykmeldtArbeidsforhold = arbeidsforhold,
        )
        val sykmeldt = pdlService.getPersonOrThrowApiError(linemanagerRevoke.employeeIdentificationNumber)
        NameValidator.validateEmployeeLastName(sykmeldt, linemanagerRevoke)

        return sykmeldt
    }

    suspend fun validatePrincipalAccessToOrgnumber(
        principal: Principal,
        orgNumber: String,
        arbeidsforhold: Arbeidsforhold? = null,
    ): String? = principalAccessValidator.validatePrincipalAccessToOrgnumber(
        principal,
        orgNumber,
        arbeidsforhold,
    )
}
