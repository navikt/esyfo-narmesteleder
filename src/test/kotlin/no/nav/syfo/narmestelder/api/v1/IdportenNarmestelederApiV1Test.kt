import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.serialization.jackson.jackson
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.narmesteleder.kafka.FakeSykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.client.TexasHttpClient

class IdportenNarmestelederApiV1Test : DescribeSpec({
    val texasClientMock = mockk<TexasHttpClient>()
    val narmesteLederRelasjon = narmesteLederRelasjon()
    val fakeAaregClient = FakeAaregClient(
        juridiskOrgnummer = narmesteLederRelasjon.organisasjonsnummer,
        arbeidsstedOrgnummer = narmesteLederRelasjon.organisasjonsnummer,
    )
    val aaregService = AaregService(fakeAaregClient)
    val pdlService = PdlService(FakePdlClient())
    val narmestelederKafkaService =
        NarmestelederKafkaService(FakeSykemeldingNLKafkaProducer(), pdlService, aaregService)
    val narmestelederKafkaServiceSpy = spyk(narmestelederKafkaService)
    val fakeAltinnTilgangerClient = FakeAltinnTilgangerClient()
    val altinnTilgangerServiceMock = AltinnTilgangerService(fakeAltinnTilgangerClient)
    val altinnTilgangerServiceSpy = spyk(altinnTilgangerServiceMock)
    beforeTest {
        clearAllMocks()
        fakeAltinnTilgangerClient.usersWithAccess.clear()
    }
    fun withTestApplication(
        fn: suspend ApplicationTestBuilder.() -> Unit
    ) {
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
                    registerApiV1(narmestelederKafkaServiceSpy, texasClientMock, altinnTilgangerServiceSpy)
                }
            }
            fn(this)
        }
    }
    describe("POST /narmesteleder") {
        it("should return 202 Accepted for valid payload") {
            withTestApplication {
                // Arrange
                val callerPid = "12345678910"
                texasClientMock.defaultMocks(pid = callerPid, acr = "Level4")
                val narmesteLederRelasjon = narmesteLederRelasjon()
                fakeAltinnTilgangerClient.usersWithAccess.add(callerPid to narmesteLederRelasjon.organisasjonsnummer)
                // Act
                val response = client.post("/idporten/api/v1/narmesteleder") {
                    bearerAuth("Bearer token")
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederRelasjon)
                }

                // Assert
                response.status shouldBe HttpStatusCode.Accepted
                coVerify(exactly = 1) {
                    narmestelederKafkaServiceSpy.sendNarmesteLederRelation(
                        eq(narmesteLederRelasjon),
                        eq(
                            NlResponseSource.LPS
                        ),
                        eq(narmesteLederRelasjon.organisasjonsnummer),
                    )
                }
            }
        }
        it("should return 403 Forbidden when caller lacks altinn tilgang to organisasjonsnumer") {
            withTestApplication {
                // Arrange
                val callerPid = "12345678910"
                texasClientMock.defaultMocks(pid = callerPid, acr = "Level4")
                val narmesteLederRelasjon = narmesteLederRelasjon()
                // Act
                val response = client.post("/idporten/api/v1/narmesteleder") {
                    bearerAuth("Bearer token")
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederRelasjon)
                }

                // Assert
                response.status shouldBe HttpStatusCode.Forbidden
                coVerify(exactly = 0) {
                    narmestelederKafkaServiceSpy.sendNarmesteLederRelation(
                        eq(narmesteLederRelasjon),
                        eq(
                            NlResponseSource.LPS
                        ),
                        eq(narmesteLederRelasjon.organisasjonsnummer),
                    )
                }
            }
        }

        it("should return 400 Bad Request for invalid payload") {
            withTestApplication {
                texasClientMock.defaultMocks()
                // Arrange
                // Act
                val response = client.post("/idporten/api/v1/narmesteleder") {
                    bearerAuth("Bearer token")
                    contentType(ContentType.Application.Json)
                    setBody("""{ "navn": "Ola Nordmann" }""")
                }

                // Assert
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                coVerify { narmestelederKafkaServiceSpy wasNot Called }
            }
        }
    }

    describe("POST /narmesteleder/avkreft") {
        it("should return 202 Accepted for valid payload") {
            withTestApplication {
                // Arrange
                val narmesteLederAvkreft = narmesteLederAvkreft()
                val callerPid = "12345678910"
                texasClientMock.defaultMocks(pid = callerPid, acr = "Level4")
                fakeAltinnTilgangerClient.usersWithAccess.add(callerPid to narmesteLederAvkreft.organisasjonsnummer)
                // Act
                val response = client.post("/idporten/api/v1/narmesteleder/avkreft") {
                    bearerAuth("Bearer token")
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederAvkreft)
                }

                // Assert
                response.status shouldBe HttpStatusCode.Accepted
                coVerify(exactly = 1) {
                    narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                        eq(narmesteLederAvkreft), eq(
                            NlResponseSource.LPS
                        )
                    )
                }
            }
        }

        it("should return 400 Bad Request for invalid payload") {
            withTestApplication {
                texasClientMock.defaultMocks()
                // Arrange
                // Act
                val response = client.post("/idporten/api/v1/narmesteleder/avkreft") {
                    bearerAuth("Bearer token")
                    contentType(ContentType.Application.Json)
                    setBody("""{ "navn": "Ola Nordmann" }""")
                }

                // Assert
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                coVerify { narmestelederKafkaServiceSpy wasNot Called }
            }
        }

        it("should return 403 Forbidden if caller lacks altinn tilgang for organisasjonsnumer") {
            withTestApplication {
                // Arrange
                val narmesteLederAvkreft = narmesteLederAvkreft()
                val callerPid = "12345678910"
                texasClientMock.defaultMocks(pid = callerPid, acr = "Level4")
                // Act
                val response = client.post("/idporten/api/v1/narmesteleder/avkreft") {
                    bearerAuth("Bearer token")
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederAvkreft)
                }

                // Assert
                response.status shouldBe HttpStatusCode.Forbidden
                coVerify(exactly = 0) {
                    narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                        eq(narmesteLederAvkreft), eq(
                            NlResponseSource.LPS
                        )
                    )
                }
            }
        }
    }
})
