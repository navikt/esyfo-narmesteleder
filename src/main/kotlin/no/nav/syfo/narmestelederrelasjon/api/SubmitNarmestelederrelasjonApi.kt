package no.nav.syfo.narmestelederrelasjon.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.api.model.Linemanager
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.application.SubmitNarmestelederrelasjonCommand
import no.nav.syfo.narmestelederrelasjon.application.SubmitNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.application.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.observability.countSubmittedNarmestelederrelasjon
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.api.tryReceive
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient
import java.util.Locale

const val SUBMIT_NARMESTELEDERRELASJON_PATH = "/linemanager"

fun Route.registerSubmitNarmestelederrelasjonApi(
    submit: SubmitNarmestelederrelasjonUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(SUBMIT_NARMESTELEDERRELASJON_PATH) {
        method(HttpMethod.Post) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }
            handle {
                val principal = call.getMyPrincipal()
                val request = call.tryReceive<Linemanager>()
                val result = submit.execute(
                    SubmitNarmestelederrelasjonCommand(
                        employeeIdent = PersonIdent(request.employeeIdentificationNumber.value),
                        employeeLastName = request.lastName,
                        organizationNumber = OrganizationNumber(request.orgNumber.value),
                        manager = ManagerContactInput(
                            personIdent = PersonIdent(request.manager.nationalIdentificationNumber.value),
                            lastName = request.manager.lastName,
                            email = request.manager.email,
                            mobile = request.manager.mobile,
                        ),
                        accessSubject = principal.toOrganizationAccessSubject(),
                    ),
                )
                countSubmittedNarmestelederrelasjon(result.throwIfRejected().source)
                call.respond(HttpStatusCode.Accepted)
            }
        }
    }
}

internal fun SubmitNarmestelederrelasjonResult.throwIfRejected(): SubmitNarmestelederrelasjonResult.Established = when (this) {
    is SubmitNarmestelederrelasjonResult.Established -> this
    is SubmitNarmestelederrelasjonResult.InvalidManagerContactDetails ->
        throw ApiErrorException.BadRequestException(
            issues.joinToString(prefix = "Invalid manager contact details: ", separator = "; ") {
                "${it.field.name.lowercase(Locale.ROOT)}: ${it.reason.message}"
            },
            type = ErrorType.INVALID_FORMAT,
            isAlreadyLogged = true,
        )
    is SubmitNarmestelederrelasjonResult.AccessDenied -> throw reason.toForbiddenException(organizationNumber)
    is SubmitNarmestelederrelasjonResult.EstablishRejected -> when (val rejection = reason) {
        is EstablishNarmestelederrelasjonResult.NoActiveSykmelding ->
            throw ApiErrorException.BadRequestException(
                "No active sick leave found for the given organization number: ${rejection.organizationNumber.value}",
                type = ErrorType.NO_ACTIVE_SICK_LEAVE,
            )
        is EstablishNarmestelederrelasjonResult.NoEmployment -> throw ApiErrorException.BadRequestException(
            when (rejection.reason) {
                EmploymentResult.NONE -> "Employee on sick leave is missing employment in any organization"
                EmploymentResult.NOT_IN_ORGANIZATION ->
                    "Employee on sick leave is missing employment in the organization indicated in the request"
                EmploymentResult.IN_ORGANIZATION -> error("An existing employment cannot be a rejection")
            },
            type = ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
        )
        EstablishNarmestelederrelasjonResult.PersonNotFound ->
            throw ApiErrorException.BadRequestException("Could not find person in PDL")
        is EstablishNarmestelederrelasjonResult.ManagerNameMismatch ->
            throw ApiErrorException.BadRequestException(
                "Last name for linemanager does not correspond with registered value for the given national identification number",
                type = ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
            )
        is EstablishNarmestelederrelasjonResult.EmployeeNameMismatch ->
            throw ApiErrorException.BadRequestException(
                "Last name for employee on sick leave does not correspond with registered value for the given national identification number",
                type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
            )
        is EstablishNarmestelederrelasjonResult.Published -> error("A published relation cannot be a rejection")
    }
}
