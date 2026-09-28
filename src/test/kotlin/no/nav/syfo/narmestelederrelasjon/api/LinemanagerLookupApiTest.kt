package no.nav.syfo.narmestelederrelasjon.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import createMockToken
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
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
import io.mockk.mockk
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerLookupRequest
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerLookupResponse
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerResponse
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.FakeActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.LookupActiveNarmestelederUseCase
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.time.Instant
import java.util.UUID

class LinemanagerLookupApiTest :
    DescribeSpec({
        val texasHttpClient = mockk<TexasHttpClient>()
        val lookupDb = FakeActiveNarmestelederrelasjonRepository()
        val lookupService = LookupActiveNarmestelederUseCase(lookupDb)
        val callingApp = "calling-app-id"
        val sykmeldtFnr = PersonIdent("12345678901")
        val orgnummer = OrganizationNumber("123456789")
        val narmestelederId = UUID.fromString("c8d10801-a0cc-4d94-a9ab-0088e850d4f4")

        fun withTestApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                client = createClient {
                    install(ContentNegotiation) {
                        jackson {
                            registerKotlinModule()
                            registerModule(JavaTimeModule())
                            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        }
                    }
                }
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        route(INTERNAL_API_V1_PATH) {
                            install(AddTokenIssuerPlugin)
                            registerLineManagerLookupApi(
                                lookupActiveNarmesteleder = lookupService,
                                texasHttpClient = texasHttpClient,
                                preAuthorizedApps = setOf(callingApp),
                            )
                        }
                    }
                }
                test()
            }
        }

        beforeTest {
            lookupDb.reset()
            coEvery { texasHttpClient.introspectToken("azuread", any()) } returns TexasIntrospectionResponse(
                active = true,
                azp = callingApp,
            )
        }

        describe("POST /internal/api/v1/lookup") {
            it("returns the active line manager with split email addresses") {
                lookupDb.rows = listOf(
                    ActiveNarmestelederrelasjon(
                        id = narmestelederId,
                        managerIdent = PersonIdent("10987654321"),
                        managerEmail = " leder@example.com, , annen@example.com ",
                        activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
                    )
                )

                withTestApplication {
                    val response = client.post("/internal/api/v1/lookup") {
                        contentType(ContentType.Application.Json)
                        setBody(LinemanagerLookupRequest(sykmeldtFnr.value, orgnummer.value))
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<LinemanagerLookupResponse>() shouldBe LinemanagerLookupResponse(
                        lineManager = LinemanagerResponse(
                            id = narmestelederId,
                            nationalIdentificationNumber = "10987654321",
                            emailAddresses = listOf("leder@example.com", "annen@example.com"),
                        )
                    )
                }
            }

            it("returns null when no active line manager exists") {
                withTestApplication {
                    val response = client.post("/internal/api/v1/lookup") {
                        contentType(ContentType.Application.Json)
                        setBody(LinemanagerLookupRequest(sykmeldtFnr.value, orgnummer.value))
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<LinemanagerLookupResponse>() shouldBe LinemanagerLookupResponse(null)
                }
            }

            it("rejects missing organizationNumber without a database lookup") {
                withTestApplication {
                    val response = client.post("/internal/api/v1/lookup") {
                        contentType(ContentType.Application.Json)
                        setBody(LinemanagerLookupRequest(sykmeldtFnr.value, null))
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    lookupDb.lookups shouldBe emptyList()
                }
            }

            it("rejects invalid employeeNationalIdentificationNumber with INVALID_FORMAT") {
                withTestApplication {
                    val response = client.post("/internal/api/v1/lookup") {
                        contentType(ContentType.Application.Json)
                        setBody(LinemanagerLookupRequest("invalid", orgnummer.value))
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                }
            }

            it("rejects applications outside the pre-authorized allowlist") {
                coEvery { texasHttpClient.introspectToken("azuread", any()) } returns TexasIntrospectionResponse(
                    active = true,
                    azp = "other-app-id",
                )

                withTestApplication {
                    val response = client.post("/internal/api/v1/lookup") {
                        contentType(ContentType.Application.Json)
                        setBody(LinemanagerLookupRequest(sykmeldtFnr.value, orgnummer.value))
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    })
