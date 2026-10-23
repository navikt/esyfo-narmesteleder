package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.application.submit.SubmitNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactField
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationReason
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

private const val PATH = "/api/v1/linemanager"

class SubmitNarmestelederrelasjonHttpMappingTest :
    FunSpec({
        val org = OrganizationNumber("123456789")

        listOf(
            Triple(
                EstablishNarmestelederrelasjonResult.NoActiveSykmelding(org),
                ErrorType.NO_ACTIVE_SICK_LEAVE,
                "No active sick leave found for the given organization number: 123456789",
            ),
            Triple(
                EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.None),
                ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
                "Employee on sick leave is missing employment in any organization",
            ),
            Triple(
                EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.NotInOrganization),
                ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
                "Employee on sick leave is missing employment in the organization indicated in the request",
            ),
            Triple(EstablishNarmestelederrelasjonResult.PersonNotFound, ErrorType.BAD_REQUEST, "Could not find person in PDL"),
            Triple(
                EstablishNarmestelederrelasjonResult.ManagerNameMismatch,
                ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
                "Last name for linemanager does not correspond with registered value for the given national identification number",
            ),
            Triple(
                EstablishNarmestelederrelasjonResult.EmployeeNameMismatch,
                ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
                "Last name for employee on sick leave does not correspond with registered value for the given national identification number",
            ),
        ).forEach { (rejection, type, message) ->
            test("maps establish rejection $rejection to an unlogged 400 with its error type and message") {
                val error = shouldThrow<ApiErrorException.BadRequestException> {
                    SubmitNarmestelederrelasjonResult.EstablishRejected(rejection).throwIfRejected()
                }
                val response = error.toApiError(PATH)
                response.status shouldBe HttpStatusCode.BadRequest
                response.type shouldBe type
                response.message shouldBe message
                error.isAlreadyLogged shouldBe false
            }
        }

        test("maps invalid contact details to an already logged INVALID_FORMAT") {
            val error = shouldThrow<ApiErrorException.BadRequestException> {
                SubmitNarmestelederrelasjonResult.InvalidManagerContactDetails(
                    listOf(
                        ManagerContactValidationIssue(ManagerContactField.MOBILE, ManagerContactValidationReason.PHONE_NUMBER_MUST_NOT_BE_BLANK),
                        ManagerContactValidationIssue(ManagerContactField.EMAIL, ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID),
                    ),
                ).throwIfRejected()
            }
            val response = error.toApiError(PATH)
            response.status shouldBe HttpStatusCode.BadRequest
            response.type shouldBe ErrorType.INVALID_FORMAT
            response.message shouldBe
                "Invalid manager contact details: mobile: PhoneNumber must not be blank; email: EmailAddress must be a valid email address"
            error.isAlreadyLogged shouldBe true
        }

        test("maps access denial to the shared 403 contract") {
            val error = shouldThrow<ApiErrorException.ForbiddenException> {
                SubmitNarmestelederrelasjonResult.AccessDenied(DenialReason.MISSING_RESOURCE_ACCESS, org).throwIfRejected()
            }
            val response = error.toApiError(PATH)
            response.status shouldBe HttpStatusCode.Forbidden
            response.type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
            response.message shouldBe "User lacks access to required Altinn resource for organization: 123456789"
        }

        test("maps upstream unavailable to an unlogged server error carrying the failure") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val error = shouldThrow<ApiErrorException.InternalServerErrorException> {
                SubmitNarmestelederrelasjonResult.UpstreamUnavailable(failure).throwIfRejected()
            }
            error.upstreamFailure shouldBe failure
            error.cause shouldBe failure.cause
            error.isAlreadyLogged shouldBe false
            val response = error.toApiError(PATH)
            response.status shouldBe HttpStatusCode.InternalServerError
            response.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
            response.message shouldBe "An upstream service is unavailable"
        }
    })
