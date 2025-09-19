import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.AaregClient
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.narmesteleder.kafka.FakeSykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.client.TexasHttpClient

class NarmestelederApiV1Test : DescribeSpec({
    val pdlService = PdlService(FakePdlClient())
    val narmestelederKafkaService = NarmestelederKafkaService(FakeSykemeldingNLKafkaProducer(), pdlService)
    val narmestelederKafkaServiceSpy = spyk(narmestelederKafkaService)
    val texasHttpClientMock = mockk<TexasHttpClient>()
    val aaregClientMock = mockk<AaregClient>()
    val aaregService = AaregService(aaregClientMock)

    beforeTest {
        clearAllMocks()
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
                    registerApiV1(narmestelederKafkaServiceSpy, texasHttpClientMock, aaregService)
                }
            }
            fn(this)
        }
    }
    describe("POST /narmesteleder") {
        it("should return 202 Accepted for valid payload") {
            withTestApplication {
                // Arrange
                val narmesteLederRelasjon = narmesteLederRelasjon()
                texasHttpClientMock.defaultMocks(
                    consumer = DefaultOrganization.copy(
                        ID = "0192:${narmesteLederRelasjon.organisasjonsnummer}"
                    )
                )
                aaregClientMock.defaultMocks(narmesteLederRelasjon.organisasjonsnummer)
                // Act
                val response = client.post("/api/v1/narmesteleder") {
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederRelasjon)
                    bearerAuth(createMockToken(narmesteLederRelasjon.organisasjonsnummer))
                }

                // Assert
                response.status shouldBe HttpStatusCode.Accepted
                coVerify(exactly = 1) {
                    narmestelederKafkaServiceSpy.sendNarmesteLederRelation(
                        eq(narmesteLederRelasjon), eq(
                            NlResponseSource.LPS
                        )
                    )
                }
            }
        }

        it("should return 400 Bad Request for invalid payload") {
            withTestApplication {
                texasHttpClientMock.defaultMocks()
                aaregClientMock.defaultMocks()

                // Arrange
                // Act
                val response = client.post("/api/v1/narmesteleder") {
                    contentType(ContentType.Application.Json)
                    setBody("""{ "navn": "Ola Nordmann" }""")
                    bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                }

                // Assert
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                coVerify { narmestelederKafkaServiceSpy wasNot Called }
            }
        }

        it("should return 401 unauthorized for missing token") {
            withTestApplication {
                texasHttpClientMock.defaultMocks()
                aaregClientMock.defaultMocks()

                // Arrange
                // Act
                val response = client.post("/api/v1/narmesteleder") {
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederRelasjon())
                }

                // Assert
                response.status shouldBe HttpStatusCode.Unauthorized
                response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                coVerify { narmestelederKafkaServiceSpy wasNot Called }
            }
        }

        it("should return 401 unauthorized for invalid token issuer") {
            withTestApplication {
                texasHttpClientMock.defaultMocks()
                aaregClientMock.defaultMocks()

                // Arrange
                // Act
                val response = client.post("/api/v1/narmesteleder") {
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederRelasjon())
                    bearerAuth(createMockToken(consumerId = "", issuer = "invalid"))
                }

                // Assert
                response.status shouldBe HttpStatusCode.Unauthorized
                response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                coVerify { narmestelederKafkaServiceSpy wasNot Called }
            }
        }
    }

    describe("POST /narmesteleder/avkreft") {
        it("should return 202 Accepted for valid payload") {
            withTestApplication {
                texasHttpClientMock.defaultMocks()
                aaregClientMock.defaultMocks()

                // Arrange
                val narmesteLederAvkreft = narmesteLederAvkreft()
                // Act
                val response = client.post("/api/v1/narmesteleder/avkreft") {
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederAvkreft)
                    bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
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
                texasHttpClientMock.defaultMocks()
                aaregClientMock.defaultMocks()

                // Arrange
                // Act
                val response = client.post("/api/v1/narmesteleder/avkreft") {
                    contentType(ContentType.Application.Json)
                    setBody("""{ "navn": "Ola Nordmann" }""")
                    bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                }

                // Assert
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                coVerify { narmestelederKafkaServiceSpy wasNot Called }
            }
        }
    }
})
