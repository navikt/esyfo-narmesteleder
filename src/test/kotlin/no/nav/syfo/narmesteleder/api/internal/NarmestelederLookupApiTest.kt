package no.nav.syfo.narmesteleder.api.internal

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
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.db.ActiveNarmestelederEntity
import no.nav.syfo.narmesteleder.db.INarmestelederLookupDb
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.time.Instant

class NarmestelederLookupApiTest :
    DescribeSpec({
        val texasHttpClient = mockk<TexasHttpClient>()
        val lookupDb = mockk<INarmestelederLookupDb>()
        val lookupService = NarmestelederLookupService(lookupDb)
        val callingApp = "calling-app-id"
        val sykmeldtFnr = PersonalIdentificationNumber("12345678901")
        val orgnummer = OrganizationNumber("123456789")

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
                        route("/internal") {
                            install(AddTokenIssuerPlugin)
                            registerNarmestelederLookupApi(lookupService, texasHttpClient, setOf(callingApp))
                        }
                    }
                }
                test()
            }
        }

        beforeTest {
            coEvery { texasHttpClient.introspectToken("azuread", any()) } returns TexasIntrospectionResponse(
                active = true,
                azp = callingApp,
            )
        }

        describe("POST /internal/narmesteleder") {
            it("returns the active line manager with split email addresses") {
                coEvery { lookupDb.findActiveNarmesteledere(sykmeldtFnr, orgnummer) } returns listOf(
                    ActiveNarmestelederEntity(
                        narmestelederFnr = PersonalIdentificationNumber("10987654321"),
                        narmestelederEpost = " leder@example.com, , annen@example.com ",
                        aktivFom = Instant.parse("2026-01-01T00:00:00Z"),
                    )
                )

                withTestApplication {
                    val response = client.post("/internal/narmesteleder") {
                        contentType(ContentType.Application.Json)
                        setBody(NarmestelederLookupRequest(sykmeldtFnr.value, orgnummer.value))
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<NarmestelederLookupResponse>() shouldBe NarmestelederLookupResponse(
                        narmesteLeder = NarmesteLederResponse(
                            fnr = "10987654321",
                            epostadresser = listOf("leder@example.com", "annen@example.com"),
                        )
                    )
                }
            }

            it("returns null when no active line manager exists") {
                coEvery { lookupDb.findActiveNarmesteledere(sykmeldtFnr, orgnummer) } returns emptyList()

                withTestApplication {
                    val response = client.post("/internal/narmesteleder") {
                        contentType(ContentType.Application.Json)
                        setBody(NarmestelederLookupRequest(sykmeldtFnr.value, orgnummer.value))
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<NarmestelederLookupResponse>() shouldBe NarmestelederLookupResponse(null)
                }
            }

            it("rejects applications outside the pre-authorized allowlist") {
                coEvery { texasHttpClient.introspectToken("azuread", any()) } returns TexasIntrospectionResponse(
                    active = true,
                    azp = "other-app-id",
                )

                withTestApplication {
                    val response = client.post("/internal/narmesteleder") {
                        contentType(ContentType.Application.Json)
                        setBody(NarmestelederLookupRequest(sykmeldtFnr.value, orgnummer.value))
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    })
