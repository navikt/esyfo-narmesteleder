package no.nav.syfo.narmestelederrelasjon.api

import DefaultOrganization
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import createMockToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import linemanager
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.api.v1.registerLinemanagerApiV1
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederrelasjon.application.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.application.HasActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.SubmitNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.application.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactField
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationReason
import no.nav.syfo.narmestelederrelasjon.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederrelasjon.observability.COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS
import no.nav.syfo.narmestelederrelasjon.observability.COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse

private val organization = OrganizationNumber("123456789")
private val noMatch = ManagerLastNameMatch.NoMatch(null, false)

class SubmitNarmestelederrelasjonRouteTest :
    FunSpec({
        test("published submissions return 202 and increment the corresponding existing counter") {
            listOf(
                Triple("https://test.maskinporten.no", COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS, "maskinporten"),
                Triple("https://tokenx.example.com", COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER, "tokenx"),
            ).forEach { (issuer, counter, provider) ->
                val fixture = SubmitFixture()
                fixture.authorize(provider)
                val before = counter.count()
                fixture.withApplication {
                    val response = client.postSubmission(issuer = issuer)
                    response.status shouldBe HttpStatusCode.Accepted
                }
                fixture.verifyIntrospection(provider)
                counter.count() shouldBe before + 1.0
            }
        }

        test("every rejected relation result maps to the legacy HTTP contract") {
            val cases = listOf(
                HttpCase(EstablishNarmestelederrelasjonResult.NoActiveSykmelding(organization), ErrorType.NO_ACTIVE_SICK_LEAVE, "No active sick leave found for the given organization number: 123456789"),
                HttpCase(EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.NONE), ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG, "Employee on sick leave is missing employment in any organization"),
                HttpCase(EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.NOT_IN_ORGANIZATION), ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG, "Employee on sick leave is missing employment in the organization indicated in the request"),
                HttpCase(EstablishNarmestelederrelasjonResult.PersonNotFound, ErrorType.BAD_REQUEST, "Could not find person in PDL"),
                HttpCase(EstablishNarmestelederrelasjonResult.ManagerNameMismatch(noMatch), ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH, "Last name for linemanager does not correspond with registered value for the given national identification number"),
                HttpCase(EstablishNarmestelederrelasjonResult.EmployeeNameMismatch(noMatch), ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH, "Last name for employee on sick leave does not correspond with registered value for the given national identification number"),
            )
            cases.forEach { case ->
                val rejection = SubmitNarmestelederrelasjonResult.EstablishRejected(case.result)
                shouldThrow<ApiErrorException.BadRequestException> { rejection.throwIfRejected() }.isAlreadyLogged shouldBe false
                val fixture = SubmitFixture(relationResult = case.result)
                fixture.authorize("tokenx")
                fixture.withApplication {
                    val response = client.postSubmission()
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().let {
                        it.type shouldBe case.type
                        it.message shouldBe case.message
                    }
                }
            }
        }

        test("contact rejection precedes access and preserves the sanitized validation message") {
            val fixture = SubmitFixture(access = OrganizationAccess { _, _ -> error("Access must not be checked") })
            fixture.authorize("tokenx")
            fixture.withApplication {
                val bad = linemanager().copy(manager = linemanager().manager.copy(mobile = "bad-number", email = "bad email"))
                val response = client.postSubmission(body = bad)
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().let {
                    it.type shouldBe ErrorType.INVALID_FORMAT
                    it.message shouldBe "Invalid manager contact details: mobile: PhoneNumber must contain only digits, with an optional leading plus sign; email: EmailAddress must not contain whitespace"
                }
            }
            shouldThrow<ApiErrorException.BadRequestException> {
                SubmitNarmestelederrelasjonResult.InvalidManagerContactDetails(
                    listOf(ManagerContactValidationIssue(ManagerContactField.MOBILE, ManagerContactValidationReason.PHONE_NUMBER_MUST_NOT_BE_BLANK)),
                ).throwIfRejected()
            }.isAlreadyLogged shouldBe true
        }

        test("organization and resource denials have the legacy message, type and logging flag") {
            val request = linemanager()
            listOf(
                Triple(DenialReason.MISSING_ORGANIZATION_ACCESS, ErrorType.MISSING_ORG_ACCESS, "User lacks access to organization: ${request.orgNumber.value}"),
                Triple(DenialReason.MISSING_RESOURCE_ACCESS, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "User lacks access to required Altinn resource for organization: ${request.orgNumber.value}"),
                Triple(DenialReason.SYSTEM_USER_REJECTED, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "System user does not have access to nav_syfo_oppgi-narmesteleder resource"),
            ).forEach { (reason, type, message) ->
                shouldThrow<ApiErrorException.ForbiddenException> {
                    SubmitNarmestelederrelasjonResult.AccessDenied(reason, organization).throwIfRejected()
                }.isAlreadyLogged shouldBe (reason == DenialReason.SYSTEM_USER_REJECTED)
                val fixture = SubmitFixture(access = OrganizationAccess { _, _ -> OrganizationAccessResult.Denied(reason) })
                fixture.authorize(if (reason == DenialReason.SYSTEM_USER_REJECTED) "maskinporten" else "tokenx")
                fixture.withApplication {
                    val response = client.postSubmission(issuer = if (reason == DenialReason.SYSTEM_USER_REJECTED) "https://test.maskinporten.no" else "https://tokenx.example.com", body = request)
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().let {
                        it.type shouldBe type
                        it.message shouldBe message
                    }
                }
            }
        }

        test("missing tokens cannot reach POST, revoke, requirement or internal relation endpoints") {
            val fixture = SubmitFixture()
            fixture.withApplication {
                listOf(
                    client.post("/api/v1/linemanager"),
                    client.post("/api/v1/linemanager/revoke"),
                    client.put("/api/v1/linemanager/requirement/00000000-0000-0000-0000-000000000001"),
                    client.get("/api/v1/linemanager/requirement/00000000-0000-0000-0000-000000000001"),
                    client.get("/internal/api/v1/linemanager/00000000-0000-0000-0000-000000000001"),
                    client.delete("/internal/api/v1/linemanager/00000000-0000-0000-0000-000000000001"),
                ).forEach { it.status shouldBe HttpStatusCode.Unauthorized }
            }
        }

        test("tokens without the required scope, issuer or security level cannot submit") {
            val invalidScope = SubmitFixture(access = OrganizationAccess { _, _ -> error("Access must not be checked") })
            invalidScope.authorize("maskinporten", scope = "invalid-scope")
            invalidScope.withApplication {
                val response = client.postSubmission(issuer = "https://test.maskinporten.no")
                response.status shouldBe HttpStatusCode.Unauthorized
                response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
            }

            val invalidIssuer = SubmitFixture(access = OrganizationAccess { _, _ -> error("Access must not be checked") })
            invalidIssuer.withApplication {
                val response = client.postSubmission(issuer = "invalid")
                response.status shouldBe HttpStatusCode.Unauthorized
                response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
            }

            val level3 = SubmitFixture(access = OrganizationAccess { _, _ -> error("Access must not be checked") })
            level3.authorize("tokenx", acr = "Level3")
            level3.withApplication {
                client.postSubmission().status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("invalid request bodies retain INVALID_FORMAT") {
            val fixture = SubmitFixture()
            fixture.authorize("tokenx")
            fixture.withApplication {
                listOf(
                    """{"name":"Ola Nordmann"}""",
                    """{"employeeIdentificationNumber":"12345678901","lastName":"Employee","orgNumber":"12345678","manager":{"nationalIdentificationNumber":"10987654321","lastName":"Manager","mobile":"+4790000000","email":"manager@example.test"}}""",
                ).forEach { body ->
                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(createMockToken("11223344556", issuer = "https://tokenx.example.com"))
                        setBody(body)
                    }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                }
            }
        }

        test("PDL failures propagate to StatusPages as internal errors") {
            val fixture = SubmitFixture(failure = PdlRequestException("PDL unavailable"))
            fixture.authorize("tokenx")
            fixture.withApplication {
                val response = client.postSubmission()
                response.status shouldBe HttpStatusCode.InternalServerError
                response.body<ApiError>().let {
                    it.type shouldBe ErrorType.INTERNAL_SERVER_ERROR
                    it.message shouldBe "Internal server error"
                }
            }
        }
    })

private data class HttpCase(
    val result: EstablishNarmestelederrelasjonResult,
    val type: ErrorType,
    val message: String,
)

private class SubmitFixture(
    private val relationResult: EstablishNarmestelederrelasjonResult = EstablishNarmestelederrelasjonResult.Published(ManagerLastNameMatch.Exact(false)),
    private val access: OrganizationAccess = OrganizationAccess { _, _ -> OrganizationAccessResult.Granted },
    private val failure: Exception? = null,
) {
    private val texas = mockk<TexasHttpClient>()

    fun authorize(
        provider: String,
        acr: String = "Level4",
        scope: String = no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE,
    ) {
        if (provider == "tokenx") {
            coEvery { texas.introspectToken("tokenx", any()) } returns TexasIntrospectionResponse(active = true, acr = acr, pid = "11223344556")
        } else {
            coEvery { texas.introspectToken("maskinporten", any()) } returns TexasIntrospectionResponse(
                active = true,
                scope = scope,
                consumer = DefaultOrganization,
                authorizationDetails = listOf(
                    no.nav.syfo.texas.client.AuthorizationDetail(
                        type = "urn:altinn:systemuser",
                        systemuserOrg = DefaultOrganization,
                        systemuserId = listOf("some-user-id"),
                        systemId = "some-system-id",
                    ),
                ),
            )
        }
    }

    fun verifyIntrospection(provider: String) {
        coVerify(exactly = 1) { texas.introspectToken(provider, any()) }
    }

    fun withApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            client = createClient {
                install(ContentNegotiation) {
                    jackson {
                        registerKotlinModule()
                        registerModule(JavaTimeModule())
                    }
                }
            }
            application {
                installContentNegotiation()
                installStatusPages()
                routing {
                    route(API_V1_PATH) {
                        install(AddTokenIssuerPlugin)
                        registerLinemanagerApiV1(
                            mockk<NarmestelederKafkaService>(),
                            mockk<ValidationService>(),
                            texas,
                            mockk<LinemanagerRequirementRESTHandler>(),
                            mockk<HasActiveNarmestelederrelasjonUseCase>(),
                            mockk<FulfillNarmestelederbehovUseCase>(),
                        )
                        registerSubmitNarmestelederrelasjonApi(
                            SubmitNarmestelederrelasjonUseCase(
                                access,
                                EstablishNarmestelederrelasjon {
                                    failure?.let { throw it }
                                    relationResult
                                },
                            ),
                            texas,
                        )
                    }
                    route(INTERNAL_API_V1_PATH) {
                        install(AddTokenIssuerPlugin)
                        registerNarmestelederrelasjonApi(mockk(), mockk(), texas)
                    }
                }
            }
            test()
        }
    }
}

private suspend fun HttpClient.postSubmission(
    issuer: String = "https://tokenx.example.com",
    body: Linemanager = linemanager(),
) = post("/api/v1/linemanager") {
    contentType(ContentType.Application.Json)
    bearerAuth(createMockToken("11223344556", issuer = issuer))
    setBody(body)
}
