import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NlBehovRESTHandler
import no.nav.syfo.narmesteleder.api.v1.domain.NarmestelederAktorer
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.domain.NlBehovRead
import no.nav.syfo.narmesteleder.domain.NlBehovWrite
import no.nav.syfo.narmesteleder.kafka.FakeSykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient

class NarmestelederApiV1Test : DescribeSpec({
    val pdlService = PdlService(FakePdlClient())
    val texasHttpClientMock = mockk<TexasHttpClient>()
    val narmesteLederRelasjon = narmesteLederRelasjon()
    val fakeAaregClient = FakeAaregClient()
    val aaregService = AaregService(fakeAaregClient)
    val narmestelederKafkaService =
        NarmestelederKafkaService(FakeSykemeldingNLKafkaProducer())
    val narmestelederKafkaServiceSpy = spyk(narmestelederKafkaService)
    val fakeAltinnTilgangerClient = FakeAltinnTilgangerClient()
    val altinnTilgangerServiceMock = AltinnTilgangerService(fakeAltinnTilgangerClient)
    val altinnTilgangerServiceSpy = spyk(altinnTilgangerServiceMock)
    val fakeDinesykmeldteClient = FakeDinesykmeldteClient()
    val dineSykmelteService = DinesykmeldteService(fakeDinesykmeldteClient)
    val validationService = ValidationService(pdlService, aaregService, altinnTilgangerServiceSpy, dineSykmelteService)
    val validationServiceSpy = spyk(validationService)
    val tokenXIssuer = "https://tokenx.nav.no"
    val fakeRepo = FakeNarmestelederDb()

    val narmesteLederService = NarmestelederService(
        nlDb = fakeRepo,
        persistLeesahNlBehov = true,
        aaregService = aaregService,
        pdlService = pdlService,
        ioDispatcher = Dispatchers.Default,
    )
    val nlBehovHandler = NlBehovRESTHandler(
        narmesteLederService = narmesteLederService,
        validationService = validationServiceSpy,
        narmestelederKafkaService = narmestelederKafkaServiceSpy
    )
    beforeTest {
        clearAllMocks()
        fakeAltinnTilgangerClient.usersWithAccess.clear()
        fakeAaregClient.arbeidsForholdForIdent.clear()
        fakeRepo.clear()
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
                    registerApiV1(
                        narmestelederKafkaServiceSpy,
                        texasHttpClientMock,
                        validationServiceSpy,
                        nlBehovHandler
                    )
                }
            }
            fn(this)
        }
    }
    describe("POST /narmesteleder") {
        describe("Maskinporten token") {
            it("should return 202 Accepted for valid payload") {
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(
                            ID = "0192:${narmesteLederRelasjon.organisasjonsnummer}"
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    fakeAaregClient.arbeidsForholdForIdent.put(
                        narmesteLederRelasjon.sykmeldtFnr,
                        listOf(narmesteLederRelasjon.organisasjonsnummer to narmesteLederRelasjon.organisasjonsnummer)
                    )
                    fakeAaregClient.arbeidsForholdForIdent.put(
                        narmesteLederRelasjon.leder.fnr,
                        listOf(narmesteLederRelasjon.organisasjonsnummer to narmesteLederRelasjon.organisasjonsnummer)
                    )
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
                            eq(narmesteLederRelasjon),
                            narmestelederAktorer = any<NarmestelederAktorer>(),
                            eq(NlResponseSource.LPS),
                        )
                    }
                }
            }

            it("should return 400 Bad Request for invalid payload") {
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(
                            ID = "0192:${narmesteLederRelasjon.organisasjonsnummer}"
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
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
                    // Arrange
                    texasHttpClientMock.defaultMocks()
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

            it("should return 401 unauthorized for missing valid maskinporten scope") {
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(
                            ID = "0192:${narmesteLederRelasjon.organisasjonsnummer}"
                        ),
                        scope = "invalid-scope",
                    )
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
                    // Arrange
                    texasHttpClientMock.defaultMocks()
                    // Act
                    val response = client.post("/api/v1/narmesteleder") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon())
                        bearerAuth(createMockToken(ident = "", issuer = "invalid"))
                    }

                    // Assert
                    response.status shouldBe HttpStatusCode.Unauthorized
                    response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                    coVerify { narmestelederKafkaServiceSpy wasNot Called }
                }
            }
        }
        describe("TokenX token") {
            it("should return 202 Accepted for valid payload") {
                withTestApplication {
                    // Arrange
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(
                        acr = "Level4",
                        pid = callerPid
                    )
                    fakeAltinnTilgangerClient.usersWithAccess.add(callerPid to narmesteLederRelasjon.organisasjonsnummer)
                    fakeAaregClient.arbeidsForholdForIdent.put(
                        narmesteLederRelasjon.sykmeldtFnr,
                        listOf(narmesteLederRelasjon.organisasjonsnummer to narmesteLederRelasjon.organisasjonsnummer)
                    )
                    fakeAaregClient.arbeidsForholdForIdent.put(
                        narmesteLederRelasjon.leder.fnr,
                        listOf(narmesteLederRelasjon.organisasjonsnummer to narmesteLederRelasjon.organisasjonsnummer)
                    )
                    // Act
                    val response = client.post("/api/v1/narmesteleder") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon)
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }
                    // Assert
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.sendNarmesteLederRelation(
                            eq(narmesteLederRelasjon),
                            narmestelederAktorer = any<NarmestelederAktorer>(),
                            eq(NlResponseSource.LPS),
                        )
                    }
                    coVerify(exactly = 1) {
                        validationServiceSpy.validateNarmesteleder(
                            eq(narmesteLederRelasjon),
                            any()
                        )
                    }
                }
            }

            it("should return 403 when caller lacks access to organisasjonsnummer for nl relasjon") {
                withTestApplication {
                    // Arrange
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(
                        acr = "Level4",
                        pid = callerPid
                    )
                    // Act
                    val response = client.post("/api/v1/narmesteleder") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon)
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    // Assert
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }

            it("should return 403 when caller lacks Level4") {
                withTestApplication {
                    // Arrange
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(
                        acr = "Level3",
                        pid = callerPid
                    )
                    fakeAltinnTilgangerClient.usersWithAccess.add(callerPid to narmesteLederRelasjon.organisasjonsnummer)
                    // Act
                    val response = client.post("/api/v1/narmesteleder") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon)
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    // Assert
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }

    describe("POST /narmesteleder/avkreft") {
        it("should return 202 Accepted for valid payload") {
            val narmesteLederAvkreft = narmesteLederAvkreft()
            withTestApplication {
                // Arrange
                texasHttpClientMock.defaultMocks(
                    consumer = DefaultOrganization.copy(
                        ID = "0192:${narmesteLederAvkreft.organisasjonsnummer}"
                    ),
                    scope = MASKINPORTEN_NL_SCOPE,
                )
                val narmesteLederAvkreft = narmesteLederAvkreft
                fakeAaregClient.arbeidsForholdForIdent.clear()
                fakeAaregClient.arbeidsForholdForIdent.put(
                    narmesteLederAvkreft.sykmeldtFnr,
                    listOf(narmesteLederAvkreft.organisasjonsnummer to narmesteLederRelasjon.organisasjonsnummer)
                )
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
                coVerify(exactly = 1) {
                    validationServiceSpy.validateNarmestelederAvkreft(
                        eq(narmesteLederAvkreft),
                        any()
                    )
                }
            }
        }

        it("should return 400 if sykmeldt lacks arbeidsforhold for orgnummer") {

            withTestApplication {
                // Arrange
                texasHttpClientMock.defaultMocks(
                    consumer = DefaultOrganization.copy(
                        ID = "0192:${narmesteLederRelasjon.organisasjonsnummer}"
                    ),
                    scope = MASKINPORTEN_NL_SCOPE,
                )
                val narmesteLederAvkreft = narmesteLederAvkreft()
                // Act
                val response = client.post("/api/v1/narmesteleder/avkreft") {
                    contentType(ContentType.Application.Json)
                    setBody(narmesteLederAvkreft)
                    bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                }

                // Assert
                response.status shouldBe HttpStatusCode.BadRequest
                coVerify(exactly = 0) {
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
                texasHttpClientMock.defaultMocks(
                    consumer = DefaultOrganization.copy(
                        ID = "0192:${narmesteLederRelasjon.organisasjonsnummer}"
                    ),
                    scope = MASKINPORTEN_NL_SCOPE,
                )
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


        describe("/narmesteleder/behov endpoints") {
            val sykmeldtFnr = narmesteLederRelasjon.sykmeldtFnr
            val lederFnr = narmesteLederRelasjon.leder.fnr
            val orgnummer = narmesteLederRelasjon.organisasjonsnummer

            fun NarmesteLederRelasjonerWrite.toNlBehovWrite(): NlBehovWrite = NlBehovWrite(
                sykmeldtFnr = sykmeldtFnr,
                orgnummer = organisasjonsnummer,
                narmesteLederFnr = leder.fnr,
                leesahStatus = "ACTIVE"
            )

            suspend fun seedBehov(): UUID {
                fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                narmesteLederService.createNewNlBehov(narmesteLederRelasjon.toNlBehovWrite())
                return fakeRepo.lastId() ?: error("No behov seeded")
            }

            it("GET /behov/{id} 200 for Maskinporten token") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val behovId = seedBehov()
                    val response = client.get("/api/v1/narmesteleder/behov/$behovId") {
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<NlBehovRead>()
                    body.id shouldBe behovId
                    body.orgnummer shouldBe orgnummer
                    body.sykmeldtFnr shouldBe sykmeldtFnr
                }
            }

            it("GET /behov/{id} 404 when behov not found") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val randomId = UUID.randomUUID()
                    val response = client.get("/api/v1/narmesteleder/behov/$randomId") {
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.NotFound
                    response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                }
            }

            it("GET /behov/{id} 403 when Maskinporten principal org mismatch") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:999999999"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val behovId = seedBehov()
                    val response = client.get("/api/v1/narmesteleder/behov/$behovId") {
                        bearerAuth(createMockToken("999999999"))
                    }
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                }
            }

            it("PUT /behov/{id} 202 updates behov and sends kafka message") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val behovId = seedBehov()
                    val updatedRelasjon = narmesteLederRelasjon.copy(
                        leder = narmesteLederRelasjon.leder.copy(fnr = narmesteLederRelasjon.leder.fnr.reversed())
                    )
                    fakeAaregClient.arbeidsForholdForIdent.put(
                        updatedRelasjon.leder.fnr,
                        listOf(orgnummer to orgnummer)
                    )
                    val response = client.put("/api/v1/narmesteleder/behov/$behovId") {
                        contentType(ContentType.Application.Json)
                        setBody(updatedRelasjon)
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.sendNarmesteLederRelation(
                            eq(updatedRelasjon), any(), eq(NlResponseSource.leder)
                        )
                    }
                    val stored = fakeRepo.findBehovById(behovId) ?: error("Stored behov missing")
                    stored.behovStatus.name shouldBe "PENDING"
                }
            }

            it("PUT /behov/{id} 404 when behov not found") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val randomId = UUID.randomUUID()
                    fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                    fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                    val response = client.put("/api/v1/narmesteleder/behov/$randomId") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon)
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.NotFound
                    response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                }
            }

            it("PUT /behov/{id} 400 invalid payload") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val behovId = seedBehov()
                    val response = client.put("/api/v1/narmesteleder/behov/$behovId") {
                        contentType(ContentType.Application.Json)
                        setBody("""{ "foo": "bar" }""")
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                }
            }

            it("PUT /behov/{id} 403 when principal lacks org access") {
                withTestApplication {
                    val behovId = seedBehov()
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:000000000"), // mismatch org
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                    fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                    val response = client.put("/api/v1/narmesteleder/behov/$behovId") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon)
                        bearerAuth(createMockToken("000000000"))
                    }
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                }
            }
        }
    }
})
