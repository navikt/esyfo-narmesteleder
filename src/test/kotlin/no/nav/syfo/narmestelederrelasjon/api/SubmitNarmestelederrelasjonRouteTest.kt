package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.createMockToken
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.linemanager
import no.nav.syfo.narmestelederrelasjon.api.model.Linemanager
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.application.submit.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.observability.COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS
import no.nav.syfo.narmestelederrelasjon.observability.COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.platform.api.ApiError
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.api.createJsonClient
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.stubMaskinportenSystemUser
import no.nav.syfo.platform.auth.texas.stubTokenX
import no.nav.syfo.platform.observability.assertSingleUpstreamFailureLog
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

private val organization = OrganizationNumber("123456789")

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

        test("a rejected relation returns its legacy 400") {
            val fixture = SubmitFixture(relationResult = EstablishNarmestelederrelasjonResult.NoActiveSykmelding(organization))
            fixture.authorize("tokenx")
            fixture.withApplication {
                val response = client.postSubmission()
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().let {
                    it.type shouldBe ErrorType.NO_ACTIVE_SICK_LEAVE
                    it.message shouldBe "No active sick leave found for the given organization number: 123456789"
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
        }

        test("access denial returns the legacy 403") {
            val request = linemanager()
            val fixture = SubmitFixture(access = OrganizationAccess { _, _ -> OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS) })
            fixture.authorize("tokenx")
            fixture.withApplication {
                val response = client.postSubmission(body = request)
                response.status shouldBe HttpStatusCode.Forbidden
                response.body<ApiError>().let {
                    it.type shouldBe ErrorType.MISSING_ORG_ACCESS
                    it.message shouldBe "User lacks access to organization: ${request.orgNumber.value}"
                }
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

        test("invalid identifiers in the body return 400 with the validation message") {
            val fixture = SubmitFixture(access = OrganizationAccess { _, _ -> error("Access must not be checked") })
            fixture.authorize("tokenx")
            fun submission(employee: String = "12345678901", org: String = "123456789", manager: String = "10987654321"): String {
                val managerJson =
                    """{"nationalIdentificationNumber":"$manager","lastName":"Manager","mobile":"+4790000000","email":"manager@example.test"}"""
                return """{"orgNumber":"$org","employeeIdentificationNumber":"$employee","lastName":"Employee","manager":$managerJson}"""
            }
            fixture.withApplication {
                listOf(
                    submission(employee = "1234567890") to "PersonalIdentificationNumber must be exactly 11 digits",
                    submission(org = "12345678") to "OrganizationNumber must be exactly 9 digits",
                    submission(manager = "1098765432a") to "PersonalIdentificationNumber must be exactly 11 digits",
                    // The first invalid identifier in the JSON document decides the message.
                    submission(employee = "1234567890", org = "12345678") to "OrganizationNumber must be exactly 9 digits",
                ).forEach { (body, message) ->
                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(createMockToken("11223344556", issuer = "https://tokenx.example.com"))
                        setBody(body)
                    }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().let {
                        it.type shouldBe ErrorType.INVALID_FORMAT
                        it.message shouldBe message
                    }
                }
            }
        }

        test("upstream failure returns HTTP 500, logs once at the edge and is not counted") {
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 500, errors = listOf(UpstreamError("server_error")))
            val fixture = SubmitFixture(relationResult = EstablishNarmestelederrelasjonResult.UpstreamUnavailable(failure))
            fixture.authorize("tokenx")
            val before = COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER.count()
            assertSingleUpstreamFailureLog(upstream = "aareg") {
                fixture.withApplication {
                    val response = client.postSubmission()
                    response.status shouldBe HttpStatusCode.InternalServerError
                    response.body<ApiError>().let {
                        it.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
                        it.message shouldBe "An upstream service is unavailable"
                    }
                }
            }
            COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER.count() shouldBe before
        }
    })

private class SubmitFixture(
    private val relationResult: EstablishNarmestelederrelasjonResult = EstablishNarmestelederrelasjonResult.Published,
    private val access: OrganizationAccess = OrganizationAccess { _, _ -> OrganizationAccessResult.Granted(organizationName = null) },
) {
    private val texas = mockk<TexasHttpClient>()

    fun authorize(
        provider: String,
        acr: String = "Level4",
        scope: String = no.nav.syfo.platform.auth.texas.MASKINPORTEN_NL_SCOPE,
    ) {
        if (provider == "tokenx") {
            texas.stubTokenX(pid = "11223344556", acr = acr)
        } else {
            texas.stubMaskinportenSystemUser(scope = scope)
        }
    }

    fun verifyIntrospection(provider: String) {
        coVerify(exactly = 1) { texas.introspectToken(provider, any()) }
    }

    fun withApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
        testRouteApplication(
            routes = {
                apiV1 {
                    registerSubmitNarmestelederrelasjonApi(SubmitNarmestelederrelasjonUseCase(access) { relationResult }, texas)
                }
            },
        ) {
            client = createJsonClient()
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
