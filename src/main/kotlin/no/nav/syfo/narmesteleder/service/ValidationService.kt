package no.nav.syfo.narmesteleder.service

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.ContactValidationIssue
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.normalizeContactDetails
import no.nav.syfo.narmesteleder.service.validators.ArbeidsforholdValidator
import no.nav.syfo.narmesteleder.service.validators.NameValidator
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.util.logger

class ValidationService(
    private val pdlService: PdlService,
    private val aaregService: AaregService,
    private val principalAccessValidator: PrincipalAccessValidator,
    private val sickLeaveValidator: SickLeaveValidator,
) {
    private val logger = logger()

    fun normalizeLinemanagerPayload(
        linemanager: Linemanager,
        context: String,
    ): Linemanager = linemanager.copy(
        manager = normalizeManagerPayload(
            manager = linemanager.manager,
            context = context,
        )
    )

    fun normalizeManagerPayload(
        manager: Manager,
        context: String,
    ): Manager {
        val validation = manager.normalizeContactDetails()
        logContactValidationIssues(validation.issues, context)
        if (validation.issues.isNotEmpty()) {
            throw ApiErrorException.BadRequestException(
                errorMessage = validation.issues.toBadRequestMessage(),
                type = ErrorType.INVALID_FORMAT,
            )
        }
        return validation.manager
    }

    suspend fun validateLinemanager(
        linemanager: Linemanager,
        principal: Principal,
        validateEmployeeLastName: Boolean = true,
    ): LinemanagerActors {
        principalAccessValidator.validatePrincipalAccessToOrgnumber(
            principal,
            linemanager.orgNumber.value,
        )
        sickLeaveValidator.validateActiveSickLeave(linemanager.employeeIdentificationNumber.value, linemanager.orgNumber.value)
        val sykmeldtArbeidsforhold =
            aaregService.findArbeidsforholdByPersonIdent(linemanager.employeeIdentificationNumber.value)

        ArbeidsforholdValidator.validateSmArbeidsforhold(
            sykmeldtArbeidsforhold = sykmeldtArbeidsforhold,
            orgNumberInRequest = linemanager.orgNumber.value,
        )

        val sykmeldt = pdlService.getPersonOrThrowApiError(linemanager.employeeIdentificationNumber.value)
        val leder = pdlService.getPersonOrThrowApiError(linemanager.manager.nationalIdentificationNumber.value)
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
            aaregService.findArbeidsforholdByPersonIdent(linemanagerRevoke.employeeIdentificationNumber.value)
        principalAccessValidator.validatePrincipalAccessToOrgnumber(
            principal,
            linemanagerRevoke.orgNumber.value,
        )
        ArbeidsforholdValidator.validateNarmesteLederAvkreft(
            orgNumberInRequest = linemanagerRevoke.orgNumber.value,
            sykmeldtArbeidsforhold = arbeidsforhold,
        )
        val sykmeldt = pdlService.getPersonOrThrowApiError(linemanagerRevoke.employeeIdentificationNumber.value)
        NameValidator.validateEmployeeLastName(sykmeldt, linemanagerRevoke)

        return sykmeldt
    }

    suspend fun validatePrincipalAccessToOrgnumber(
        principal: Principal,
        orgNumber: OrganizationNumber,
    ): String? = principalAccessValidator.validatePrincipalAccessToOrgnumber(principal, orgNumber.value)

    private fun logContactValidationIssues(
        issues: List<ContactValidationIssue>,
        context: String,
    ) {
        issues.forEach { issue ->
            logger.warn(
                "ContactValidationIssue: Received manager payload with invalid {} for {}. Rejecting request. Reason: {}",
                issue.fieldName,
                context,
                issue.reason,
            )
        }
    }
}

private fun List<ContactValidationIssue>.toBadRequestMessage(): String = joinToString(
    prefix = "Invalid manager contact details: ",
    separator = "; ",
) { "${it.fieldName}: ${it.reason}" }
