package no.nav.syfo.narmestelederbehov.api

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.toUpstreamUnavailableException
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovResult
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput

fun Manager.toManagerContactInput(): ManagerContactInput = ManagerContactInput(
    PersonIdent(nationalIdentificationNumber.value),
    lastName,
    email,
    mobile,
)

fun FulfillNarmestelederbehovResult.throwIfRejected() {
    when (this) {
        is FulfillNarmestelederbehovResult.Fulfilled -> Unit
        is FulfillNarmestelederbehovResult.UpstreamUnavailable -> throw failure.toUpstreamUnavailableException()
        is FulfillNarmestelederbehovResult.InvalidManagerContactDetails -> {
            throw ApiErrorException.BadRequestException(
                issues.joinToString(prefix = "Invalid manager contact details: ", separator = "; ") {
                    "${it.field.name.lowercase(java.util.Locale.ROOT)}: ${it.reason.message}"
                },
                type = ErrorType.INVALID_FORMAT,
                isAlreadyLogged = true,
            )
        }
        FulfillNarmestelederbehovResult.NotFound,
        FulfillNarmestelederbehovResult.BehovMissingAfterPublication ->
            throw ApiErrorException.NotFoundException("A LinemanagerRequirement was not found", isAlreadyLogged = true)
        is FulfillNarmestelederbehovResult.AccessDenied -> {
            throw accessDeniedException(reason = reason, organizationNumber = organizationNumber)
        }
        is FulfillNarmestelederbehovResult.NoActiveSykmelding -> throw ApiErrorException.BadRequestException(
            "No active sick leave found for the given organization number: ${organizationNumber.value}",
            type = ErrorType.NO_ACTIVE_SICK_LEAVE,
            isAlreadyLogged = true,
        )
        is FulfillNarmestelederbehovResult.NoEmployment -> throw ApiErrorException.BadRequestException(
            when (reason) {
                EmploymentResult.None -> "Employee on sick leave is missing employment in any organization"
                EmploymentResult.NotInOrganization ->
                    "Employee on sick leave is missing employment in the organization indicated in the request"
            },
            type = ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
            isAlreadyLogged = true,
        )
        FulfillNarmestelederbehovResult.PersonNotFound ->
            throw ApiErrorException.BadRequestException("Could not find person in PDL", isAlreadyLogged = true)
        FulfillNarmestelederbehovResult.ManagerNameMismatch -> throw ApiErrorException.BadRequestException(
            "Last name for linemanager does not correspond with registered value for the given national identification number",
            type = ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
            isAlreadyLogged = true,
        )
    }
}
