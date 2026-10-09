package no.nav.syfo.narmestelederbehov.api

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovResult
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactField
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationReason
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import org.slf4j.LoggerFactory

class FulfillmentHttpMappingTest :
    FunSpec({
        val org = OrganizationNumber("123456789")
        val cases = listOf(
            Case(FulfillNarmestelederbehovResult.NotFound, HttpStatusCode.NotFound, ErrorType.NOT_FOUND, "A LinemanagerRequirement was not found"),
            Case(FulfillNarmestelederbehovResult.BehovMissingAfterPublication, HttpStatusCode.NotFound, ErrorType.NOT_FOUND, "A LinemanagerRequirement was not found"),
            Case(
                FulfillNarmestelederbehovResult.AccessDenied(DenialReason.MISSING_ORGANIZATION_ACCESS, org),
                HttpStatusCode.Forbidden,
                ErrorType.MISSING_ORG_ACCESS,
                "User lacks access to organization: 123456789"
            ),
            Case(
                FulfillNarmestelederbehovResult.AccessDenied(DenialReason.MISSING_RESOURCE_ACCESS, org),
                HttpStatusCode.Forbidden,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "User lacks access to required Altinn resource for organization: 123456789"
            ),
            Case(
                FulfillNarmestelederbehovResult.AccessDenied(DenialReason.SYSTEM_USER_REJECTED, org),
                HttpStatusCode.Forbidden,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "System user does not have access to nav_syfo_oppgi-narmesteleder resource"
            ),
            Case(
                FulfillNarmestelederbehovResult.NoActiveSykmelding(org),
                HttpStatusCode.BadRequest,
                ErrorType.NO_ACTIVE_SICK_LEAVE,
                "No active sick leave found for the given organization number: 123456789"
            ),
            Case(
                FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.None),
                HttpStatusCode.BadRequest,
                ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
                "Employee on sick leave is missing employment in any organization"
            ),
            Case(
                FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.NotInOrganization),
                HttpStatusCode.BadRequest,
                ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
                "Employee on sick leave is missing employment in the organization indicated in the request"
            ),
            Case(
                FulfillNarmestelederbehovResult.PersonNotFound,
                HttpStatusCode.BadRequest,
                ErrorType.BAD_REQUEST,
                "Could not find person in PDL"
            ),
            Case(
                FulfillNarmestelederbehovResult.ManagerNameMismatch,
                HttpStatusCode.BadRequest,
                ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
                "Last name for linemanager does not correspond with registered value for the given national identification number"
            ),
            Case(
                FulfillNarmestelederbehovResult.InvalidManagerContactDetails(
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
            test("maps ${case.result} to the legacy HTTP error") {
                val error = shouldThrow<ApiErrorException> { case.result.throwIfRejected() }
                val response = error.toApiError("/api/v1/linemanager/requirement/id")
                response.status shouldBe case.status
                response.type shouldBe case.type
                response.message shouldBe case.message
                error.isAlreadyLogged shouldBe true
            }
        }

        test("maps upstream unavailable to an unlogged server error carrying the failure") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val error = shouldThrow<ApiErrorException.InternalServerErrorException> {
                FulfillNarmestelederbehovResult.UpstreamUnavailable(failure).throwIfRejected()
            }
            error.upstreamFailure shouldBe failure
            error.cause shouldBe failure.cause
            error.isAlreadyLogged shouldBe false
            val response = error.toApiError("/api/v1/linemanager/requirement/id")
            response.status shouldBe HttpStatusCode.InternalServerError
            response.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
            response.message shouldBe "An upstream service is unavailable"
        }

        test("invalid contact mapping emits no second rejection event") {
            val logger = LoggerFactory.getLogger(FulfillNarmestelederbehovResult::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                val invalid = FulfillNarmestelederbehovResult.InvalidManagerContactDetails(
                    listOf(
                        ManagerContactValidationIssue(ManagerContactField.MOBILE, ManagerContactValidationReason.PHONE_NUMBER_MUST_NOT_BE_BLANK),
                        ManagerContactValidationIssue(ManagerContactField.EMAIL, ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID),
                    ),
                )
                shouldThrow<ApiErrorException> { invalid.throwIfRejected() }.isAlreadyLogged shouldBe true
                appender.list.size shouldBe 0
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    })

private data class Case(
    val result: FulfillNarmestelederbehovResult,
    val status: HttpStatusCode,
    val type: ErrorType,
    val message: String,
)
