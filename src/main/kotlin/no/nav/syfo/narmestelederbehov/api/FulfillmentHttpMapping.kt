package no.nav.syfo.narmestelederbehov.api

import io.ktor.http.HttpStatusCode
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.organisasjonstilgang.api.toForbiddenApiError
import no.nav.syfo.platform.api.ApiError
import no.nav.syfo.platform.api.ApiRejection
import no.nav.syfo.platform.api.ErrorType

fun Manager.toManagerContactInput(): ManagerContactInput = ManagerContactInput(
    nationalIdentificationNumber,
    lastName,
    email,
    mobile,
)

fun FulfillNarmestelederbehovError.toApiRejection(): ApiRejection = when (this) {
    is FulfillNarmestelederbehovError.UpstreamUnavailable -> ApiRejection(
        ApiError(HttpStatusCode.InternalServerError, ErrorType.UPSTREAM_SERVICE_UNAVAILABLE, "An upstream service is unavailable"),
        alreadyLogged = false,
        upstreamFailure = failure,
    )

    is FulfillNarmestelederbehovError.InvalidManagerContactDetails -> ApiRejection(
        ApiError(
            HttpStatusCode.BadRequest,
            ErrorType.INVALID_FORMAT,
            issues.joinToString(prefix = "Invalid manager contact details: ", separator = "; ") {
                "${it.field.name.lowercase(java.util.Locale.ROOT)}: ${it.reason.message}"
            },
        ),
        alreadyLogged = true,
    )

    FulfillNarmestelederbehovError.NotFound,
    FulfillNarmestelederbehovError.BehovMissingAfterPublication,
    -> ApiRejection(
        ApiError(HttpStatusCode.NotFound, ErrorType.NOT_FOUND, "A LinemanagerRequirement was not found"),
        alreadyLogged = true,
    )

    is FulfillNarmestelederbehovError.AccessDenied -> ApiRejection(
        reason.toForbiddenApiError(organizationNumber),
        alreadyLogged = true,
    )

    is FulfillNarmestelederbehovError.NoActiveSykmelding -> ApiRejection(
        ApiError(
            HttpStatusCode.BadRequest,
            ErrorType.NO_ACTIVE_SICK_LEAVE,
            "No active sick leave found for the given organization number: ${organizationNumber.value}",
        ),
        alreadyLogged = true,
    )

    is FulfillNarmestelederbehovError.NoEmployment -> ApiRejection(
        ApiError(
            HttpStatusCode.BadRequest,
            ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
            when (reason) {
                EmploymentResult.None -> "Employee on sick leave is missing employment in any organization"

                EmploymentResult.NotInOrganization ->
                    "Employee on sick leave is missing employment in the organization indicated in the request"
            },
        ),
        alreadyLogged = true,
    )

    FulfillNarmestelederbehovError.PersonNotFound -> ApiRejection(
        ApiError(HttpStatusCode.BadRequest, ErrorType.BAD_REQUEST, "Could not find person in PDL"),
        alreadyLogged = true,
    )

    FulfillNarmestelederbehovError.ManagerNameMismatch -> ApiRejection(
        ApiError(
            HttpStatusCode.BadRequest,
            ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
            "Last name for linemanager does not correspond with registered value for the given national identification number",
        ),
        alreadyLogged = true,
    )
}
