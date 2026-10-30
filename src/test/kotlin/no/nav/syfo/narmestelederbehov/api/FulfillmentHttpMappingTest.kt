package no.nav.syfo.narmestelederbehov.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactField
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationReason
import no.nav.syfo.organisasjonstilgang.application.DenialReason
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
                val rejection = case.result.toApiRejection()
                rejection.error.status shouldBe case.status
                rejection.error.type shouldBe case.type
                rejection.error.message shouldBe case.message
                rejection.error.path shouldBe null
                rejection.alreadyLogged shouldBe true
                rejection.upstreamFailure shouldBe null
            }
        }

        test("maps upstream unavailable to an unlogged server error carrying the failure") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val error = FulfillNarmestelederbehovError.UpstreamUnavailable(failure)
            val rejection = error.toApiRejection()
            rejection.upstreamFailure shouldBe failure
            rejection.alreadyLogged shouldBe false
            rejection.error.status shouldBe HttpStatusCode.InternalServerError
            rejection.error.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
            rejection.error.message shouldBe "An upstream service is unavailable"
            rejection.error.path shouldBe null
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
