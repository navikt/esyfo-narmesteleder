import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.serialization.jackson.jackson
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import io.mockk.confirmVerified
import io.mockk.spyk
import io.mockk.verify
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.narmesteleder.kafka.FakeSykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.registerApiV1

class NarmestelederApiV1Test : DescribeSpec({
    val narmestelederKafkaService = NarmestelederKafkaService(FakeSykemeldingNLKafkaProducer())
    val narmestelederKafkaServiceSpy = spyk(narmestelederKafkaService)
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
                    registerApiV1(narmestelederKafkaServiceSpy)
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
                // Act
                val response = client.post("/api/v1/narmesteleder") {
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederRelasjon)
                }

                // Assert
                response.status shouldBe HttpStatusCode.Accepted
                verify(exactly = 1) {
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
                // Arrange
                // Act
                val response = client.post("/api/v1/narmesteleder") {
                    contentType(ContentType.Application.Json)
                    setBody("""{ "navn": "Ola Nordmann" }""")
                }

                // Assert
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                verify { narmestelederKafkaServiceSpy wasNot Called }
            }
        }
    }

    describe("POST /narmesteleder/avkreft") {
        it("should return 202 Accepted for valid payload") {
            withTestApplication {
                // Arrange
                val narmesteLederAvkreft = narmesteLederAvkreft()
                // Act
                val response = client.post("/api/v1/narmesteleder/avkreft") {
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederAvkreft)
                }

                // Assert
                response.status shouldBe HttpStatusCode.Accepted
                verify(exactly = 1) {
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
                // Arrange
                // Act
                val response = client.post("/api/v1/narmesteleder/avkreft") {
                    contentType(ContentType.Application.Json)
                    setBody("""{ "navn": "Ola Nordmann" }""")
                }

                // Assert
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                verify { narmestelederKafkaServiceSpy wasNot Called }
            }
        }
    }
})
