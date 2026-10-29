package no.nav.syfo.narmestelederbehov.api

import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.application.DialogportenCompletionAttempt
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError
import no.nav.syfo.narmestelederbehov.application.FulfilledNarmestelederbehov
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactField
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationReason
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.platform.api.ApiResponse
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.observability.withProductionLogs
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

class FulfillmentHttpMappingTest :
    FunSpec({
        val org = OrganizationNumber("123456789")
        val cases = listOf(
            Case(FulfillNarmestelederbehovError.NotFound, HttpStatusCode.NotFound, ErrorType.NOT_FOUND, "A LinemanagerRequirement was not found"),
            Case(FulfillNarmestelederbehovError.BehovMissingAfterPublication, HttpStatusCode.NotFound, ErrorType.NOT_FOUND, "A LinemanagerRequirement was not found"),
            Case(
                FulfillNarmestelederbehovError.AccessDenied(DenialReason.MISSING_ORGANIZATION_ACCESS, org),
                HttpStatusCode.Forbidden,
                ErrorType.MISSING_ORG_ACCESS,
                "User lacks access to organization: 123456789"
            ),
            Case(
                FulfillNarmestelederbehovError.AccessDenied(DenialReason.MISSING_RESOURCE_ACCESS, org),
                HttpStatusCode.Forbidden,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "User lacks access to required Altinn resource for organization: 123456789"
            ),
            Case(
                FulfillNarmestelederbehovError.AccessDenied(DenialReason.SYSTEM_USER_REJECTED, org),
                HttpStatusCode.Forbidden,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "System user does not have access to nav_syfo_oppgi-narmesteleder resource"
            ),
            Case(
                FulfillNarmestelederbehovError.NoActiveSykmelding(org),
                HttpStatusCode.BadRequest,
                ErrorType.NO_ACTIVE_SICK_LEAVE,
                "No active sick leave found for the given organization number: 123456789"
            ),
            Case(
                FulfillNarmestelederbehovError.NoEmployment(EmploymentResult.None),
                HttpStatusCode.BadRequest,
                ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
                "Employee on sick leave is missing employment in any organization"
            ),
            Case(
                FulfillNarmestelederbehovError.NoEmployment(EmploymentResult.NotInOrganization),
                HttpStatusCode.BadRequest,
                ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
                "Employee on sick leave is missing employment in the organization indicated in the request"
            ),
            Case(
                FulfillNarmestelederbehovError.PersonNotFound,
                HttpStatusCode.BadRequest,
                ErrorType.BAD_REQUEST,
                "Could not find person in PDL"
            ),
            Case(
                FulfillNarmestelederbehovError.ManagerNameMismatch,
                HttpStatusCode.BadRequest,
                ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
                "Last name for linemanager does not correspond with registered value for the given national identification number"
            ),
            Case(
                FulfillNarmestelederbehovError.InvalidManagerContactDetails(
                    listOf(
                        ManagerContactValidationIssue(ManagerContactField.MOBILE, ManagerContactValidationReason.PHONE_NUMBER_MUST_NOT_BE_BLANK),
                        ManagerContactValidationIssue(ManagerContactField.EMAIL, ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID),
                    )
                ),
                HttpStatusCode.BadRequest,
                ErrorType.INVALID_FORMAT,
                "Invalid manager contact details: mobile: PhoneNumber must not be blank; email: EmailAddress must be a valid email address"
            ),
        )
        cases.forEach { case ->
            test("maps ${case.result} to its HTTP error") {
                val mapped = case.result.left().toApiResponse().shouldBeInstanceOf<ApiResponse.Rejected>()
                listOf(case.result.toApiRejection(), mapped.rejection).forEach { rejection ->
                    val response = rejection.error
                    response.status shouldBe case.status
                    response.type shouldBe case.type
                    response.message shouldBe case.message
                    response.path shouldBe null
                    rejection.alreadyLogged shouldBe true
                    rejection.upstreamFailure shouldBe null
                    rejection.cause shouldBe null
                }
            }
        }

        test("maps upstream unavailable to an unlogged server error carrying the failure") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val error = FulfillNarmestelederbehovError.UpstreamUnavailable(failure)
            val mapped = error.left().toApiResponse().shouldBeInstanceOf<ApiResponse.Rejected>()
            listOf(error.toApiRejection(), mapped.rejection).forEach { rejection ->
                rejection.upstreamFailure shouldBe failure
                rejection.cause shouldBe failure.cause
                rejection.alreadyLogged shouldBe false
                val response = rejection.error
                response.status shouldBe HttpStatusCode.InternalServerError
                response.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
                response.message shouldBe "An upstream service is unavailable"
                response.path shouldBe null
            }
        }

        test("maps fulfillment to Accepted without a response body") {
            FulfilledNarmestelederbehov(RelationSource.LPS, DialogportenCompletionAttempt.Completed)
                .right().toApiResponse() shouldBe ApiResponse.Success(HttpStatusCode.Accepted)
        }

        test("invalid contact mapping emits no second rejection event") {
            withProductionLogs(FulfillNarmestelederbehovError::class.java) { logs ->
                val invalid = FulfillNarmestelederbehovError.InvalidManagerContactDetails(
                    listOf(
                        ManagerContactValidationIssue(ManagerContactField.MOBILE, ManagerContactValidationReason.PHONE_NUMBER_MUST_NOT_BE_BLANK),
                        ManagerContactValidationIssue(ManagerContactField.EMAIL, ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID),
                    ),
                )
                invalid.toApiRejection().alreadyLogged shouldBe true
                logs.logs.size shouldBe 0
            }
        }
    })

private data class Case(
    val result: FulfillNarmestelederbehovError,
    val status: HttpStatusCode,
    val type: ErrorType,
    val message: String,
)
