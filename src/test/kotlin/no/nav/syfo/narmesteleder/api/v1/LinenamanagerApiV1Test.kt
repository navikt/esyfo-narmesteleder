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
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.api.v1.RECUIREMENT_PATH
import no.nav.syfo.narmesteleder.api.v1.REVOKE_PATH
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
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

class LinenamanagerApiV1Test : DescribeSpec({
    val pdlService = PdlService(FakePdlClient())
    val texasHttpClientMock = mockk<TexasHttpClient>()
    val narmesteLederRelasjon = linemanager()
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
    val fakePdpClient = FakePdpClient()
    val pdpService = PdpService(fakePdpClient)
    val validationService = ValidationService(pdlService, aaregService, altinnTilgangerServiceSpy, dineSykmelteService, pdpService)
    val validationServiceSpy = spyk(validationService)
    val tokenXIssuer = "https://tokenx.nav.no"
    val fakeRepo = FakeNarmestelederDb()

    val narmesteLederService = NarmestelederService(
        nlDb = fakeRepo,
        persistLeesahNlBehov = true,
        aaregService = aaregService,
        pdlService = pdlService,
        dinesykmeldteService = dineSykmelteService,
    )
    val nlBehovHandler = LinemanagerRequirementRESTHandler(
        narmesteLederService = narmesteLederService,
        validationService = validationServiceSpy,
        narmestelederKafkaService = narmestelederKafkaServiceSpy,
        altinnTilgangerService = altinnTilgangerServiceSpy,
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
    describe("POST /linemanager") {
        describe("Maskinporten token") {
            it("should return 202 Accepted for valid payload") {
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(
                            ID = "0192:${narmesteLederRelasjon.orgNumber}"
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    fakeAaregClient.arbeidsForholdForIdent[narmesteLederRelasjon.employeeIdentificationNumber] = listOf(narmesteLederRelasjon.orgNumber to narmesteLederRelasjon.orgNumber)
                    fakeAaregClient.arbeidsForholdForIdent[narmesteLederRelasjon.manager.nationalIdentificationNumber] =
                        listOf(narmesteLederRelasjon.orgNumber to narmesteLederRelasjon.orgNumber)
                    // Act
                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon)
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber))
                    }

                    // Assert
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                            eq(narmesteLederRelasjon),
                            linemanagerActors = any<LinemanagerActors>(),
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
                            ID = "0192:${narmesteLederRelasjon.orgNumber}"
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    // Act
                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        setBody("""{ "name": "Ola Nordmann" }""")
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
                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        setBody(linemanager())
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
                            ID = "0192:${narmesteLederRelasjon.orgNumber}"
                        ),
                        scope = "invalid-scope",
                    )
                    // Act
                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        setBody(linemanager())
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
                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        setBody(linemanager())
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
                    fakeAltinnTilgangerClient.usersWithAccess.clear()
                    fakeAltinnTilgangerClient.usersWithAccess.add(callerPid to narmesteLederRelasjon.orgNumber)
                    fakeAaregClient.arbeidsForholdForIdent.put(
                        narmesteLederRelasjon.employeeIdentificationNumber,
                        listOf(narmesteLederRelasjon.orgNumber to narmesteLederRelasjon.orgNumber)
                    )
                    fakeAaregClient.arbeidsForholdForIdent.put(
                        narmesteLederRelasjon.manager.nationalIdentificationNumber,
                        listOf(narmesteLederRelasjon.orgNumber to narmesteLederRelasjon.orgNumber)
                    )
                    // Act
                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon)
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }
                    // Assert
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                            eq(narmesteLederRelasjon),
                            linemanagerActors = any<LinemanagerActors>(),
                            eq(NlResponseSource.PERSONALLEDER),
                        )
                    }
                    coVerify(exactly = 1) {
                        validationServiceSpy.validateLinemanager(
                            eq(narmesteLederRelasjon),
                            any()
                        )
                    }
                }
            }

            it("should return 403 when caller lacks access to organization number for Linemanager") {
                withTestApplication {
                    // Arrange
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(
                        acr = "Level4",
                        pid = callerPid
                    )
                    // Act
                    val response = client.post("/api/v1/linemanager") {
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
                    fakeAltinnTilgangerClient.usersWithAccess.add(callerPid to narmesteLederRelasjon.orgNumber)
                    // Act
                    val response = client.post("/api/v1/linemanager") {
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

    describe("POST /linemanager/revoke") {
        it("should return 202 Accepted for valid payload") {
            val narmesteLederAvkreft = linemanagerRevoke()
            withTestApplication {
                // Arrange
                texasHttpClientMock.defaultMocks(
                    systemBrukerOrganisasjon = DefaultOrganization.copy(
                        ID = "0192:${narmesteLederAvkreft.orgNumber}"
                    ),
                    scope = MASKINPORTEN_NL_SCOPE,
                )
                val narmesteLederAvkreft = narmesteLederAvkreft
                fakeAaregClient.arbeidsForholdForIdent.clear()
                fakeAaregClient.arbeidsForholdForIdent[narmesteLederAvkreft.employeeIdentificationNumber] = listOf(narmesteLederAvkreft.orgNumber to narmesteLederRelasjon.orgNumber)
                // Act
                val response = client.post("$API_V1_PATH/$REVOKE_PATH") {
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
                    validationServiceSpy.validateLinemanagerRevoke(
                        eq(narmesteLederAvkreft),
                        any()
                    )
                }
            }
        }

        it("should return 400 if sykmeldt lacks arbeidsforhold for organization number") {

            withTestApplication {
                // Arrange
                texasHttpClientMock.defaultMocks(
                    consumer = DefaultOrganization.copy(
                        ID = "0192:${narmesteLederRelasjon.orgNumber}"
                    ),
                    scope = MASKINPORTEN_NL_SCOPE,
                )
                val narmesteLederAvkreft = linemanagerRevoke()
                // Act
                val response = client.post("$API_V1_PATH/$REVOKE_PATH") {
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
                        ID = "0192:${narmesteLederRelasjon.orgNumber}"
                    ),
                    scope = MASKINPORTEN_NL_SCOPE,
                )
                // Act
                val response = client.post("$API_V1_PATH/$REVOKE_PATH") {
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


        describe("/linemanager/requirement endpoints") {
            val sykmeldtFnr = narmesteLederRelasjon.employeeIdentificationNumber
            val lederFnr = narmesteLederRelasjon.manager.nationalIdentificationNumber
            val orgnummer = narmesteLederRelasjon.orgNumber

            fun Linemanager.toNlBehovWrite(): LinemanagerRequirementWrite = LinemanagerRequirementWrite(
                employeeIdentificationNumber = sykmeldtFnr,
                orgNumber = orgNumber,
                managerIdentificationNumber = manager.nationalIdentificationNumber,
                behovReason = BehovReason.DEAKTIVERT_LEDER,
                revokedLinemanagerId = UUID.randomUUID(),
            )

            suspend fun seedLinemanagerRequirement(): UUID {
                fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                narmesteLederService.createNewNlBehov(narmesteLederRelasjon.toNlBehovWrite())
                return fakeRepo.lastId() ?: error("No requirement seeded")
            }

            it("GET /requirement/{id} 200 with Maskinporten token") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val requirementId = seedLinemanagerRequirement()
                    val response = client.get("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<LinemanagerRequirementRead>()
                    body.id shouldBe requirementId
                    body.orgNumber shouldBe orgnummer
                    body.employeeIdentificationNumber shouldBe sykmeldtFnr
                }
            }

            it("GET /requirement/{id} 404 when requirement not found") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val randomId = UUID.randomUUID()
                    val response = client.get("$API_V1_PATH/$RECUIREMENT_PATH/$randomId") {
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.NotFound
                    response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                }
            }

            it("GET /requirement/{id} 403 when Maskinporten principal org mismatch") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:999999999"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val requirementId = seedLinemanagerRequirement()
                    val response = client.get("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                        bearerAuth(createMockToken("999999999"))
                    }
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                }
            }

            it("PUT /requirement/{id} 202 updates behov and sends kafka message") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val requirementId = seedLinemanagerRequirement()
                    val manager = manager().copy(
                        nationalIdentificationNumber = narmesteLederRelasjon
                            .manager
                            .nationalIdentificationNumber
                            .reversed()
                    )
                    fakeAaregClient.arbeidsForholdForIdent[manager.nationalIdentificationNumber] =
                        listOf(orgnummer to orgnummer)

                    val response = client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                        contentType(ContentType.Application.Json)
                        setBody(manager)
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                            match { linemanager ->
                                linemanager.employeeIdentificationNumber == sykmeldtFnr &&
                                        linemanager.orgNumber == orgnummer &&
                                        linemanager.manager.nationalIdentificationNumber == manager.nationalIdentificationNumber
                            }, any(), any()
                        )
                    }
                    val stored = fakeRepo.findBehovById(requirementId) ?: error("Stored requirement missing")
                    stored.behovStatus.name shouldBe BehovStatus.BEHOV_FULFILLED.name
                }
            }

            it("PUT /requirement/{id} 404 when behov not found") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val randomId = UUID.randomUUID()
                    fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                    fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                    val response = client.put("$API_V1_PATH/$RECUIREMENT_PATH/$randomId") {
                        contentType(ContentType.Application.Json)
                        setBody(manager())
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.NotFound
                    response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                }
            }

            it("PUT /requirement/{id} 400 invalid payload") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val requirementId = seedLinemanagerRequirement()
                    val response = client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                        contentType(ContentType.Application.Json)
                        setBody("""{ "foo": "bar" }""")
                        bearerAuth(createMockToken(orgnummer))
                    }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                }
            }

            it("PUT /requirement/{id} 403 when principal lacks org access") {
                withTestApplication {
                    val requirementId = seedLinemanagerRequirement()
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:000000000"), // mismatch org
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                    fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                    val response = client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederRelasjon.manager)
                        bearerAuth(createMockToken("000000000"))
                    }
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                }
            }
        }
    }
})
