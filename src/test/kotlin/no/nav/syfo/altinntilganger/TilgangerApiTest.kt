package no.nav.syfo.altinntilganger

import DefaultOrganization
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import createMockToken
import defaultMocks
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.mockk
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPRETT_NL_REALASJON_RESOURCE
import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.AltinnTilgangerResponse
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient

class TilgangerApiTest :
    DescribeSpec({
        val texasHttpClientMock = mockk<TexasHttpClient>()
        val fakeAltinnTilgangerClient = FakeAltinnTilgangerClient()
        val altinnTilgangerService = AltinnTilgangerService(fakeAltinnTilgangerClient)
        val userFnr = "12345678901"

        beforeTest {
            clearAllMocks()
            fakeAltinnTilgangerClient.reset()
        }

        fun withTestApp(fn: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
            testApplication {
                this.client = createClient {
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
                        route(API_V1_PATH) {
                            install(AddTokenIssuerPlugin)
                            registerTilgangerApi(altinnTilgangerService, texasHttpClientMock)
                        }
                    }
                }
                fn(this)
            }
        }

        describe("GET /api/v1/tilganger") {
            it("should return filtered organisasjoner with narmesteleder tilgang") {
                withTestApp {
                    // Arrange
                    val orgNr = "123456789"
                    fakeAltinnTilgangerClient.addAccess(userFnr, orgNr, OPPGI_NARMESTELEDER_RESOURCE)
                    texasHttpClientMock.defaultMocks(pid = userFnr, acr = "Level4")

                    // Act
                    val response = client.get("$API_V1_PATH/tilganger") {
                        bearerAuth(createMockToken(ident = userFnr, issuer = "https://tokenx.nav.no"))
                    }

                    // Assert
                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<TilgangerResponse>().organisasjoner
                    body shouldHaveSize 1
                    body[0].orgnr shouldBe orgNr
                    body[0].navn shouldBe "Test Org"
                    body[0].underenheter.shouldBeEmpty()
                }
            }

            it("should return empty list when user has no narmesteleder tilgang") {
                withTestApp {
                    // Arrange
                    texasHttpClientMock.defaultMocks(pid = userFnr, acr = "Level4")

                    // Act
                    val response = client.get("$API_V1_PATH/tilganger") {
                        bearerAuth(createMockToken(ident = userFnr, issuer = "https://tokenx.nav.no"))
                    }

                    // Assert
                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<TilgangerResponse>().organisasjoner
                    body.shouldBeEmpty()
                }
            }

            it("should include org with only altinn2 tilgang") {
                withTestApp {
                    // Arrange - directly set up a response with altinn2 tilgang in hierarki
                    val orgNr = "987654321"
                    fakeAltinnTilgangerClient.accessPolicy.add(
                        FakeAltinnTilgangerClient.FakeArbeidsforholdOversikt(
                            hasAccess = mutableListOf(userFnr),
                            altinnTilgangerResponse = AltinnTilgangerResponse(
                                isError = false,
                                hierarki = listOf(
                                    AltinnTilgang(
                                        orgnr = orgNr,
                                        altinn3Tilganger = emptySet(),
                                        altinn2Tilganger = setOf(OPPRETT_NL_REALASJON_RESOURCE),
                                        underenheter = emptyList(),
                                        navn = "Altinn2 Org",
                                        organisasjonsform = "BEDR",
                                    ),
                                ),
                                orgNrTilTilganger = mapOf(orgNr to setOf(OPPRETT_NL_REALASJON_RESOURCE)),
                                tilgangTilOrgNr = mapOf(OPPRETT_NL_REALASJON_RESOURCE to setOf(orgNr)),
                            ),
                        ),
                    )
                    texasHttpClientMock.defaultMocks(pid = userFnr, acr = "Level4")

                    // Act
                    val response = client.get("$API_V1_PATH/tilganger") {
                        bearerAuth(createMockToken(ident = userFnr, issuer = "https://tokenx.nav.no"))
                    }

                    // Assert
                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<TilgangerResponse>().organisasjoner
                    body shouldHaveSize 1
                    body[0].orgnr shouldBe orgNr
                }
            }

            // Avhengig av at brukeren har tilgang til overordnetenhet men ikke til underenhet
            // TOD: sjekk om dette Skjer faktisk i praksis?
            it("should keep hovedenhet when only underenhet has tilgang") {
                withTestApp {
                    fakeAltinnTilgangerClient.accessPolicy.add(
                        FakeAltinnTilgangerClient.FakeArbeidsforholdOversikt(
                            hasAccess = mutableListOf(userFnr),
                            altinnTilgangerResponse = AltinnTilgangerResponse(
                                isError = false,
                                hierarki = listOf(
                                    AltinnTilgang(
                                        orgnr = "100000000",
                                        altinn3Tilganger = emptySet(),
                                        altinn2Tilganger = emptySet(),
                                        underenheter = listOf(
                                            AltinnTilgang(
                                                orgnr = "200000001",
                                                altinn3Tilganger = setOf(OPPGI_NARMESTELEDER_RESOURCE),
                                                altinn2Tilganger = emptySet(),
                                                underenheter = emptyList(),
                                                navn = "Underenhet Med Tilgang",
                                                organisasjonsform = "BEDR",
                                            ),
                                            AltinnTilgang(
                                                orgnr = "200000002",
                                                altinn3Tilganger = emptySet(),
                                                altinn2Tilganger = emptySet(),
                                                underenheter = emptyList(),
                                                navn = "Underenhet Uten Tilgang",
                                                organisasjonsform = "BEDR",
                                            ),
                                        ),
                                        navn = "Hovedenhet Uten Tilgang",
                                        organisasjonsform = "AS",
                                    ),
                                ),
                                orgNrTilTilganger = mapOf("200000001" to setOf(OPPGI_NARMESTELEDER_RESOURCE)),
                                tilgangTilOrgNr = mapOf(OPPGI_NARMESTELEDER_RESOURCE to setOf("200000001")),
                            ),
                        ),
                    )
                    texasHttpClientMock.defaultMocks(pid = userFnr, acr = "Level4")

                    val response = client.get("$API_V1_PATH/tilganger") {
                        bearerAuth(createMockToken(ident = userFnr, issuer = "https://tokenx.nav.no"))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<TilgangerResponse>().organisasjoner
                    body shouldHaveSize 1
                    body[0].orgnr shouldBe "100000000"
                    body[0].navn shouldBe "Hovedenhet Uten Tilgang"
                    body[0].underenheter shouldHaveSize 1
                    body[0].underenheter[0].orgnr shouldBe "200000001"
                    body[0].underenheter[0].navn shouldBe "Underenhet Med Tilgang"
                    body[0].underenheter[0].underenheter.shouldBeEmpty()
                }
            }

            it("should return empty list when altinn proxy reports isError") {
                withTestApp {
                    fakeAltinnTilgangerClient.accessPolicy.add(
                        FakeAltinnTilgangerClient.FakeArbeidsforholdOversikt(
                            hasAccess = mutableListOf(userFnr),
                            altinnTilgangerResponse = AltinnTilgangerResponse(
                                isError = true,
                                hierarki = listOf(
                                    AltinnTilgang(
                                        orgnr = "999999999",
                                        altinn3Tilganger = setOf(OPPGI_NARMESTELEDER_RESOURCE),
                                        altinn2Tilganger = emptySet(),
                                        underenheter = emptyList(),
                                        navn = "Should Be Filtered",
                                        organisasjonsform = "BEDR",
                                    ),
                                ),
                                orgNrTilTilganger = mapOf("999999999" to setOf(OPPGI_NARMESTELEDER_RESOURCE)),
                                tilgangTilOrgNr = mapOf(OPPGI_NARMESTELEDER_RESOURCE to setOf("999999999")),
                            ),
                        ),
                    )
                    texasHttpClientMock.defaultMocks(pid = userFnr, acr = "Level4")

                    val response = client.get("$API_V1_PATH/tilganger") {
                        bearerAuth(createMockToken(ident = userFnr, issuer = "https://tokenx.nav.no"))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<TilgangerResponse>().organisasjoner.shouldBeEmpty()
                }
            }

            it("should return 403 for system principal token") {
                withTestApp {
                    texasHttpClientMock.defaultMocks(
                        pid = null,
                        consumer = DefaultOrganization,
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.get("$API_V1_PATH/tilganger") {
                        bearerAuth(createMockToken(ident = "0192:123456789"))
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                }
            }

            it("should return 401 for unauthenticated request") {
                withTestApp {
                    // Act
                    val response = client.get("$API_V1_PATH/tilganger")

                    // Assert
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
