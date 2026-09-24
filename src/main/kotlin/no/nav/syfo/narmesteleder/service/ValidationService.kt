package no.nav.syfo.narmesteleder.service

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
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
import org.slf4j.event.Level

private data class ContactValidationRejectedDetails(
    val validationIssues: List<ContactValidationIssue>,
)

private val contactValidationRejected = applicationEvent<ContactValidationRejectedDetails>(
    name = "contact_validation_rejected",
    level = Level.WARN,
    message = "Manager contact fields failed validation",
    fields = mapOf(
        "validation_issues" to {
            it.validationIssues.map { issue ->
                mapOf("field" to issue.field.name, "reason" to "INVALID_FORMAT")
            }
        },
    ),
)

class ValidationService(
    private val pdlService: PdlService,
    private val aaregService: AaregService,
    private val principalAccessValidator: PrincipalAccessValidator,
    private val sickLeaveValidator: SickLeaveValidator,
) {
    private val logger = logger()

    fun normalizeLinemanagerPayload(
        linemanager: Linemanager,
    ): Linemanager = linemanager.copy(
        manager = normalizeManagerPayload(
            manager = linemanager.manager,
        )
    )

    private fun normalizeManagerPayload(
        manager: Manager,
    ): Manager {
        val validation = manager.normalizeContactDetails()
        if (validation.issues.isNotEmpty()) {
            logger.logEvent(contactValidationRejected, ContactValidationRejectedDetails(validation.issues))
            throw ApiErrorException.BadRequestException(
                errorMessage = validation.issues.toBadRequestMessage(),
                type = ErrorType.INVALID_FORMAT,
                isAlreadyLogged = true,
            )
        }
        return validation.manager
    }

    suspend fun validateLinemanager(
        linemanager: Linemanager,
        principal: Principal,
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
        NameValidator.validateEmployeeLastName(sykmeldt, linemanager)

        return LinemanagerActors(
            employee = sykmeldt,
            manager = leder,
        )
    }

    suspend fun validateLinemanagerRevoke(
        linemanagerRevoke: LinemanagerRevoke,
        principal: Principal,
    ): Person {
        principalAccessValidator.validatePrincipalAccessToOrgnumber(
            principal,
            linemanagerRevoke.orgNumber.value,
        )
        val sykmeldt = pdlService.getPersonOrThrowApiError(linemanagerRevoke.employeeIdentificationNumber.value)
        NameValidator.validateEmployeeLastName(sykmeldt, linemanagerRevoke)

        return sykmeldt
    }

    suspend fun validatePrincipalAccessToOrgnumber(
        principal: Principal,
        orgNumber: OrganizationNumber,
    ): String? = principalAccessValidator.validatePrincipalAccessToOrgnumber(principal, orgNumber.value)
}

private fun List<ContactValidationIssue>.toBadRequestMessage(): String = joinToString(
    prefix = "Invalid manager contact details: ",
    separator = "; ",
) { "${it.fieldName}: ${it.reason}" }
