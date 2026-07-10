package no.nav.syfo.narmesteleder.api.v1

import DefaultOrganization
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import createMockToken
import defaultMocks
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import linemanager
import linemanagerRevoke
import manager
import nlBehovEntity
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.IDinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.ereg.client.Organisasjon
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerManagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerPersonRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerReadCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchCursor
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchRequest
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchResult
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.ILinemanagerSearchRepository
import no.nav.syfo.narmesteleder.kafka.FakeSykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.BehovSource
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient
import prepareGetPersonResponse
import java.time.Instant
import java.util.UUID

class LinenmanagerApiV1Test :
    DescribeSpec({
        val pdlCacheMock = mockk<PdlCache>(relaxed = true)
        val pdlService = spyk(PdlService(FakePdlClient(), pdlCacheMock))
        val texasHttpClientMock = mockk<TexasHttpClient>()
        val narmesteLederRelasjon = linemanager()
        val fakeAaregClient = FakeAaregClient()
        val aaregService = AaregService(fakeAaregClient)
        val fakeEregClient = FakeEregClient()
        val eregCache = mockk<EregCache>(relaxed = true)
        val eregService = EregService(fakeEregClient, eregCache)
        val narmestelederKafkaService =
            NarmestelederKafkaService(FakeSykmeldingNLKafkaProducer())
        val narmestelederKafkaServiceSpy = spyk(narmestelederKafkaService)
        val fakeAltinnTilgangerClient = FakeAltinnTilgangerClient()
        val altinnTilgangerServiceMock = AltinnTilgangerService(fakeAltinnTilgangerClient)
        val altinnAccessServiceSpy = spyk(altinnTilgangerServiceMock)
        val fakeDinesykmeldteClient = FakeDinesykmeldteClient()
        val dineSykmelteService: IDinesykmeldteService = DinesykmeldteService(fakeDinesykmeldteClient)
        val pdpService = mockk<PdpService>(relaxed = true)
        val principalAccessValidator = PrincipalAccessValidator(
            altinnTilgangerService = altinnAccessServiceSpy,
            pdpService = pdpService,
            eregService = eregService,
        )
        val sickLeaveValidator = SickLeaveValidator(
            dinesykmeldteService = dineSykmelteService,
        )
        val validationService =
            ValidationService(
                pdlService = pdlService,
                aaregService = aaregService,
                principalAccessValidator = principalAccessValidator,
                sickLeaveValidator = sickLeaveValidator,
            )
        val validationServiceSpy = spyk(validationService)
        val tokenXIssuer = "https://tokenx.nav.no"

        lateinit var fakeRepo: FakeNarmestelederDb
        lateinit var linemanagerSearchRepository: ILinemanagerSearchRepository
        lateinit var narmesteLederService: NarmestelederService
        lateinit var nlBehovHandler: LinemanagerRequirementRESTHandler
        lateinit var linemanagerSearchService: LinemanagerSearchService

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            fakeAltinnTilgangerClient.accessPolicy.clear()
            fakeAaregClient.arbeidsForholdForIdent.clear()
            fakeRepo = spyk(FakeNarmestelederDb())
            linemanagerSearchRepository = mockk()
            coEvery { pdlCacheMock.getPerson(any()) } returns null
            narmesteLederService =
                NarmestelederService(
                    nlDb = fakeRepo,
                    persistLeesahNlBehov = true,
                    aaregService = aaregService,
                    pdlService = pdlService,
                    dinesykmeldteService = dineSykmelteService,
                    dialogportenService = mockk<DialogportenService>(relaxed = true),
                )
            nlBehovHandler =
                LinemanagerRequirementRESTHandler(
                    narmesteLederService = narmesteLederService,
                    validationService = validationServiceSpy,
                    narmestelederKafkaService = narmestelederKafkaServiceSpy,
                )
            linemanagerSearchService =
                LinemanagerSearchService(
                    validationService = validationServiceSpy,
                    linemanagerSearchRepository = linemanagerSearchRepository,
                )
            coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns true
            fakeRepo.clear()
        }

        fun withTestApplication(fn: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                this.client =
                    createClient {
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
                            nlBehovHandler,
                            linemanagerSearchService,
                            altinnAccessServiceSpy,
                        )
                    }
                }
                fn(this)
            }
        }

        fun linemanagerSearchResult(
            cursorId: Int,
            orgNumber: OrganizationNumber = narmesteLederRelasjon.orgNumber,
            employeeFnr: String = "12345678910",
            managerFnr: String = "10987654321",
        ) = LinemanagerSearchResult(
            cursor = LinemanagerSearchCursor(cursorId),
            linemanager = LinemanagerRead(
                orgNumber = orgNumber,
                activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
                employee = LinemanagerPersonRead(
                    nationalIdentificationNumber = PersonalIdentificationNumber(employeeFnr),
                    name = Name(
                        firstName = "Ola",
                        middleName = null,
                        lastName = "Nordmann",
                    ),
                ),
                manager = LinemanagerManagerRead(
                    nationalIdentificationNumber = PersonalIdentificationNumber(managerFnr),
                    name = Name(
                        firstName = "Kari",
                        middleName = null,
                        lastName = "Nordmann",
                    ),
                    email = "kari@example.com",
                    mobile = "99999999",
                ),
            ),
        )
        describe("POST /linemanager") {
            context("Maskinporten token") {
                it("Maskinporten POST /linemanager should return 202 Accepted for valid payload") {
                    withTestApplication {
                        // Arrange
                        pdlService.prepareGetPersonResponse(narmesteLederRelasjon.manager)
                        pdlService.prepareGetPersonResponse(
                            narmesteLederRelasjon.employeeIdentificationNumber.value,
                            narmesteLederRelasjon.lastName,
                        )
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon =
                            DefaultOrganization.copy(
                                ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        fakeAaregClient.arbeidsForholdForIdent[narmesteLederRelasjon.employeeIdentificationNumber.value] =
                            listOf(narmesteLederRelasjon.orgNumber.value to narmesteLederRelasjon.orgNumber.value)
                        fakeAaregClient.arbeidsForholdForIdent[narmesteLederRelasjon.manager.nationalIdentificationNumber.value] =
                            listOf(narmesteLederRelasjon.orgNumber.value to narmesteLederRelasjon.orgNumber.value)
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(narmesteLederRelasjon)
                                bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                narmesteLederRelasjon,
                                linemanagerActors = any<LinemanagerActors>(),
                                NlResponseSource.LPS,
                            )
                        }
                    }
                }

                it("Maskinporten POST /linemanager should normalize spaces in valid phone numbers") {
                    withTestApplication {
                        val linemanagerWithSpacedPhone = narmesteLederRelasjon.copy(
                            manager = narmesteLederRelasjon.manager.copy(
                                mobile = "+47 90 00 00 00",
                                email = "leder+ø@eksempelø.no; annen@domene.no ",
                            ),
                        )
                        pdlService.prepareGetPersonResponse(linemanagerWithSpacedPhone.manager)
                        pdlService.prepareGetPersonResponse(
                            linemanagerWithSpacedPhone.employeeIdentificationNumber.value,
                            linemanagerWithSpacedPhone.lastName,
                        )
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon =
                            DefaultOrganization.copy(
                                ID = "0192:${linemanagerWithSpacedPhone.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        fakeAaregClient.arbeidsForholdForIdent[linemanagerWithSpacedPhone.employeeIdentificationNumber.value] =
                            listOf(linemanagerWithSpacedPhone.orgNumber.value to linemanagerWithSpacedPhone.orgNumber.value)
                        fakeAaregClient.arbeidsForholdForIdent[linemanagerWithSpacedPhone.manager.nationalIdentificationNumber.value] =
                            listOf(linemanagerWithSpacedPhone.orgNumber.value to linemanagerWithSpacedPhone.orgNumber.value)

                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(linemanagerWithSpacedPhone)
                                bearerAuth(createMockToken(linemanagerWithSpacedPhone.orgNumber.value))
                            }

                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                match {
                                    it.manager.mobile == "+4790000000" &&
                                        it.manager.email == "leder+ø@eksempelø.no;annen@domene.no"
                                },
                                any(),
                                NlResponseSource.LPS,
                            )
                        }
                    }
                }

                it("Maskinporten POST /linemanager should accept invalid phone and email without rejecting payload") {
                    withTestApplication {
                        val linemanagerWithInvalidContacts = narmesteLederRelasjon.copy(
                            manager = narmesteLederRelasjon.manager.copy(
                                mobile = "90-00-00-00",
                                email = "gyldig@example.com; invalid @example.com",
                            ),
                        )
                        pdlService.prepareGetPersonResponse(linemanagerWithInvalidContacts.manager)
                        pdlService.prepareGetPersonResponse(
                            linemanagerWithInvalidContacts.employeeIdentificationNumber.value,
                            linemanagerWithInvalidContacts.lastName,
                        )
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon =
                            DefaultOrganization.copy(
                                ID = "0192:${linemanagerWithInvalidContacts.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        fakeAaregClient.arbeidsForholdForIdent[linemanagerWithInvalidContacts.employeeIdentificationNumber.value] =
                            listOf(linemanagerWithInvalidContacts.orgNumber.value to linemanagerWithInvalidContacts.orgNumber.value)
                        fakeAaregClient.arbeidsForholdForIdent[linemanagerWithInvalidContacts.manager.nationalIdentificationNumber.value] =
                            listOf(linemanagerWithInvalidContacts.orgNumber.value to linemanagerWithInvalidContacts.orgNumber.value)

                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(linemanagerWithInvalidContacts)
                                bearerAuth(createMockToken(linemanagerWithInvalidContacts.orgNumber.value))
                            }

                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                match {
                                    it.manager.mobile == "90-00-00-00" &&
                                        it.manager.email == "gyldig@example.com; invalid @example.com"
                                },
                                any(),
                                NlResponseSource.LPS,
                            )
                        }
                    }
                }

                it("should return 400 Bad Request for invalid payload") {
                    withTestApplication {
                        // Arrange
                        texasHttpClientMock.defaultMocks(
                            consumer =
                            DefaultOrganization.copy(
                                ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody("""{ "name": "Ola Nordmann" }""")
                                bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                        coVerify { narmestelederKafkaServiceSpy wasNot Called }
                    }
                }

                it("should return 400 Bad Request for invalid organization number in request body") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(
                                ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(
                                    """
                                    {
                                      "employeeIdentificationNumber": "12345678901",
                                      "lastName": "Hansen",
                                      "orgNumber": "12345678",
                                      "manager": {
                                        "nationalIdentificationNumber": "10987654321",
                                        "lastName": "Jensen",
                                        "mobile": "+4790000000",
                                        "email": "leder@example.com"
                                      }
                                    }
                                    """.trimIndent(),
                                )
                                bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                            }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                        coVerify { narmestelederKafkaServiceSpy wasNot Called }
                    }
                }

                it("should return 401 unauthorized for missing token") {
                    withTestApplication {
                        // Arrange
                        texasHttpClientMock.defaultMocks()
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
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
                            consumer =
                            DefaultOrganization.copy(
                                ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                            ),
                            scope = "invalid-scope",
                        )
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
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
                        val response =
                            client.post("/api/v1/linemanager") {
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
            context("TokenX token") {
                it("TokenX POST /linemanager should return 202 Accepted for valid payload") {
                    withTestApplication {
                        // Arrange
                        pdlService.prepareGetPersonResponse(narmesteLederRelasjon.manager)
                        pdlService.prepareGetPersonResponse(
                            narmesteLederRelasjon.employeeIdentificationNumber.value,
                            narmesteLederRelasjon.lastName,
                        )
                        val callerPid = "11223344556"
                        texasHttpClientMock.defaultMocks(
                            acr = "Level4",
                            pid = callerPid,
                        )
                        fakeAltinnTilgangerClient.accessPolicy.clear()
                        fakeAltinnTilgangerClient.addAccess(callerPid, narmesteLederRelasjon.orgNumber.value)
                        fakeAaregClient.arbeidsForholdForIdent.put(
                            narmesteLederRelasjon.employeeIdentificationNumber.value,
                            listOf(narmesteLederRelasjon.orgNumber.value to narmesteLederRelasjon.orgNumber.value),
                        )
                        fakeAaregClient.arbeidsForholdForIdent.put(
                            narmesteLederRelasjon.manager.nationalIdentificationNumber.value,
                            listOf(narmesteLederRelasjon.orgNumber.value to narmesteLederRelasjon.orgNumber.value),
                        )
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(narmesteLederRelasjon)
                                bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                            }
                        // Assert
                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                narmesteLederRelasjon,
                                linemanagerActors = any<LinemanagerActors>(),
                                NlResponseSource.PERSONALLEDER,
                            )
                        }
                        coVerify(exactly = 1) {
                            validationServiceSpy.validateLinemanager(
                                narmesteLederRelasjon,
                                any(),
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
                            pid = callerPid,
                        )
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
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
                            pid = callerPid,
                        )
                        fakeAltinnTilgangerClient.addAccess(callerPid, narmesteLederRelasjon.orgNumber.value)
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
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
            it("Maskinporten POST /linemanager/revoke should return 202 Accepted for valid payload") {
                val narmesteLederAvkreft = LinemanagerRevoke(
                    employeeIdentificationNumber = PersonalIdentificationNumber("12345678901"),
                    orgNumber = OrganizationNumber("123456789"),
                    lastName = "Hansen",
                )
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon =
                        DefaultOrganization.copy(
                            ID = "0192:${narmesteLederAvkreft.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(
                        narmesteLederAvkreft.employeeIdentificationNumber.value,
                        narmesteLederAvkreft.lastName,
                    )
                    fakeAaregClient.arbeidsForholdForIdent.clear()
                    fakeAaregClient.arbeidsForholdForIdent[narmesteLederAvkreft.employeeIdentificationNumber.value] =
                        listOf(narmesteLederAvkreft.orgNumber.value to narmesteLederAvkreft.orgNumber.value)
                    // Act
                    val response =
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody(narmesteLederAvkreft)
                            bearerAuth(createMockToken(narmesteLederAvkreft.orgNumber.value))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                            narmesteLederAvkreft,
                            NlResponseSource.LPS_REVOKE,
                        )
                    }
                    coVerify(exactly = 1) {
                        validationServiceSpy.validateLinemanagerRevoke(
                            narmesteLederAvkreft,
                            any(),
                        )
                    }
                }
            }

            it("should return 400 when lastName in payload does not match the nin") {
                val narmesteLederAvkreft = linemanagerRevoke()
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon =
                        DefaultOrganization.copy(
                            ID = "0192:${narmesteLederAvkreft.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(
                        narmesteLederAvkreft.employeeIdentificationNumber.value,
                        narmesteLederAvkreft.lastName.reversed(),
                    )
                    fakeAaregClient.arbeidsForholdForIdent.clear()
                    fakeAaregClient.arbeidsForholdForIdent[narmesteLederAvkreft.employeeIdentificationNumber.value] =
                        listOf(narmesteLederAvkreft.orgNumber.value to narmesteLederRelasjon.orgNumber.value)
                    // Act
                    val response =
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody(narmesteLederAvkreft)
                            bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.BadRequest
                    val body = response.body<ApiError>()
                    body.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
                    coVerify(exactly = 0) {
                        narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                            eq(narmesteLederAvkreft),
                            eq(
                                NlResponseSource.LPS,
                            ),
                        )
                    }
                    coVerify(exactly = 1) {
                        validationServiceSpy.validateLinemanagerRevoke(
                            eq(narmesteLederAvkreft),
                            any(),
                        )
                    }
                }
            }

            it("should return 400 if sykmeldt lacks arbeidsforhold for organization number") {
                withTestApplication {
                    // Arrange
                    val narmesteLederAvkreft = linemanagerRevoke()
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(
                            ID = "0192:${narmesteLederAvkreft.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    // Act
                    val response =
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody(narmesteLederAvkreft)
                            bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().message shouldBe "Employee on sick leave is missing employment in any organization"
                    coVerify(exactly = 0) {
                        narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                            narmesteLederAvkreft,
                            NlResponseSource.LPS,
                        )
                    }
                }
            }

            it("should return 400 Bad Request for invalid payload") {
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        consumer =
                        DefaultOrganization.copy(
                            ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    // Act
                    val response =
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody("""{ "navn": "Ola Nordmann" }""")
                            bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    coVerify { narmestelederKafkaServiceSpy wasNot Called }
                }
            }
        }
        describe("/linemanager/requirement endpoints") {
            val sykmeldtFnr = narmesteLederRelasjon.employeeIdentificationNumber.value
            val lederFnr = narmesteLederRelasjon.manager.nationalIdentificationNumber.value
            val orgnummer = narmesteLederRelasjon.orgNumber.value

            fun Linemanager.toNlBehovWrite(): LinemanagerRequirementWrite = LinemanagerRequirementWrite(
                employeeIdentificationNumber = PersonalIdentificationNumber(sykmeldtFnr),
                orgNumber = OrganizationNumber(orgNumber.value),
                managerIdentificationNumber = manager.nationalIdentificationNumber,
                behovReason = BehovReason.DEAKTIVERT_LEDER,
                revokedLinemanagerId = UUID.randomUUID(),
            )

            suspend fun seedLinemanagerRequirement(): UUID {
                fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                narmesteLederService.createNewNlBehov(
                    narmesteLederRelasjon.toNlBehovWrite(),
                    behovSource = BehovSource(UUID.randomUUID().toString(), "test")
                )
                return fakeRepo.lastId() ?: error("No requirement seeded")
            }
            describe("GET /requirement/{id}") {
                it("GET /requirement/{id} 200 with Maskinporten token") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val response =
                            client.get("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.OK
                        val body = response.body<LinemanagerRequirementRead>()
                        body.id shouldBe requirementId
                        body.orgNumber.value shouldBe orgnummer
                        body.employeeIdentificationNumber.value shouldBe sykmeldtFnr
                    }
                }

                it("GET /requirement/{id} 404 when requirement not found") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val randomId = UUID.randomUUID()
                        val response =
                            client.get("$API_V1_PATH/$RECUIREMENT_PATH/$randomId") {
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.NotFound
                        response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                    }
                }

                it("GET /requirement/{id} 403 when system principal lacks access to AltinnResource for orgnumber") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:000000000"), // mismatch org
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        fakeEregClient.organisasjoner[narmesteLederRelasjon.orgNumber.value] = Organisasjon(
                            organisasjonsnummer = narmesteLederRelasjon.orgNumber.value,
                            inngaarIJuridiskEnheter = emptyList()
                        )
                        val requirementId = seedLinemanagerRequirement()
                        coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns false
                        val response =
                            client.get("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                bearerAuth(createMockToken("999999999"))
                            }
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.body<ApiError>().type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                    }
                }
            }

            describe("PUT /requirement/{id}") {
                it("PUT /requirement/{id} 202 updates behov and sends kafka message") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val manager =
                            manager().copy(
                                nationalIdentificationNumber =
                                PersonalIdentificationNumber(
                                    narmesteLederRelasjon
                                        .manager
                                        .nationalIdentificationNumber
                                        .value
                                        .reversed(),
                                ),
                            )
                        pdlService.prepareGetPersonResponse(manager)
                        fakeAaregClient.arbeidsForholdForIdent[manager.nationalIdentificationNumber.value] =
                            listOf(orgnummer to orgnummer)

                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody(manager)
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                match { linemanager ->
                                    linemanager.employeeIdentificationNumber.value == sykmeldtFnr &&
                                        linemanager.orgNumber.value == orgnummer &&
                                        linemanager.manager.nationalIdentificationNumber.value == manager.nationalIdentificationNumber.value
                                },
                                any(),
                                any(),
                            )
                        }
                        val stored = fakeRepo.findBehovById(requirementId) ?: error("Stored requirement missing")
                        stored.behovStatus.name shouldBe BehovStatus.BEHOV_FULFILLED.name
                    }
                }

                it("PUT /requirement/{id} should normalize spaces in valid phone numbers") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val spacedPhoneManager = manager().copy(
                            nationalIdentificationNumber = PersonalIdentificationNumber(
                                narmesteLederRelasjon.manager.nationalIdentificationNumber.value.reversed(),
                            ),
                            mobile = "+47 90 00 00 00",
                            email = "leder+ø@eksempelø.no; annen@domene.no ",
                        )
                        pdlService.prepareGetPersonResponse(spacedPhoneManager)
                        fakeAaregClient.arbeidsForholdForIdent[spacedPhoneManager.nationalIdentificationNumber.value] =
                            listOf(orgnummer to orgnummer)

                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody(spacedPhoneManager)
                                bearerAuth(createMockToken(orgnummer))
                            }

                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                match { linemanager ->
                                    linemanager.manager.mobile == "+4790000000" &&
                                        linemanager.manager.email == "leder+ø@eksempelø.no;annen@domene.no"
                                },
                                any(),
                                any(),
                            )
                        }
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
                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$randomId") {
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
                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody("""{ "foo": "bar" }""")
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    }
                }

                it("PUT /requirement/{id} 403 when principal lacks Altinn resource access to orgnumber") {
                    withTestApplication {
                        pdlService.prepareGetPersonResponse(narmesteLederRelasjon.manager)
                        val requirementId = seedLinemanagerRequirement()
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(ID = "0192:000000000"), // mismatch org
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns false
                        fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                        fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody(narmesteLederRelasjon.manager)
                                bearerAuth(createMockToken("000000000"))
                            }
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.body<ApiError>().type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                    }
                }
            }
            describe("GET /requirement") {
                it("GET /requirement should skip count query when all results fit in the current page") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val requirement = narmesteLederService.getLinemanagerRequirementReadById(requirementId)
                        val pageSize = 10
                        val response =
                            client.get(
                                "$API_V1_PATH/$RECUIREMENT_PATH?orgNumber=${requirement.orgNumber.value}&createdAfter=${
                                    Instant.now().minusSeconds(60)
                                }&pageSize=$pageSize",
                            ) {
                                bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                            }
                        response.status shouldBe HttpStatusCode.OK
                        val body = response.body<LinemanagerRequirementCollection>()
                        body.meta.pageSize shouldBe pageSize
                        body.meta.size shouldBe 1
                        body.meta.total shouldBe 1L
                        body.linemanagerRequirements.first().id shouldBe requirementId

                        coVerify(exactly = 1) {
                            fakeRepo.findBehovByParameters(
                                orgNumber = requirement.orgNumber.value,
                                createdAfter = any(),
                                status =
                                listOf(
                                    BehovStatus.BEHOV_CREATED,
                                    BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                                ),
                                limit = pageSize + 1, // +1 to check if there is more pages
                            )
                        }
                        coVerify(exactly = 0) {
                            fakeRepo.countBehovByParameters(
                                orgNumber = requirement.orgNumber.value,
                                createdAfter = any(),
                                status = listOf(
                                    BehovStatus.BEHOV_CREATED,
                                    BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                                ),
                            )
                        }
                    }
                }

                it("GET /requirement should count total when the current page may have more results") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val createdAfter = Instant.now().minusSeconds(60)
                        suspend fun insertRequirement(entity: NarmestelederBehovEntity) {
                            fakeRepo.insertNlBehov(entity)
                        }
                        insertRequirement(
                            nlBehovEntity().copy(
                                orgnummer = narmesteLederRelasjon.orgNumber.value,
                                hovedenhetOrgnummer = narmesteLederRelasjon.orgNumber.value,
                                behovStatus = BehovStatus.BEHOV_CREATED,
                                fornavn = "Ansatt",
                                etternavn = "En",
                            )
                        )
                        insertRequirement(
                            nlBehovEntity().copy(
                                orgnummer = narmesteLederRelasjon.orgNumber.value,
                                hovedenhetOrgnummer = narmesteLederRelasjon.orgNumber.value,
                                behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                                fornavn = "Ansatt",
                                etternavn = "To",
                            )
                        )

                        val response = client.get(
                            "$API_V1_PATH/$RECUIREMENT_PATH?orgNumber=${narmesteLederRelasjon.orgNumber.value}&createdAfter=$createdAfter&pageSize=1",
                        ) {
                            bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                        }

                        response.status shouldBe HttpStatusCode.OK
                        val body = response.body<LinemanagerRequirementCollection>()
                        body.linemanagerRequirements.shouldHaveSize(1)
                        body.meta.size shouldBe 1
                        body.meta.pageSize shouldBe 1
                        body.meta.hasMore shouldBe true
                        body.meta.total shouldBe 2L

                        coVerify(exactly = 1) {
                            fakeRepo.countBehovByParameters(
                                orgNumber = narmesteLederRelasjon.orgNumber.value,
                                createdAfter = any(),
                                status = listOf(
                                    BehovStatus.BEHOV_CREATED,
                                    BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                                ),
                            )
                        }
                    }
                }

                it("GET /requirement should return 400 for invalid orgNumber query parameter") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response =
                            client.get(
                                "$API_V1_PATH/$RECUIREMENT_PATH?orgNumber=12345678&createdAfter=${Instant.now().minusSeconds(60)}",
                            ) {
                                bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                            }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    }
                }

                it("GET /requirement should return 400 for non-digit orgNumber query parameter") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response =
                            client.get(
                                "$API_V1_PATH/$RECUIREMENT_PATH?orgNumber=12345678a&createdAfter=${Instant.now().minusSeconds(60)}",
                            ) {
                                bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                            }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    }
                }
            }
            describe("POST /linemanager/search") {
                it("returns paginated linemanager results for authorized Maskinporten principals") {
                    withTestApplication {
                        coEvery {
                            linemanagerSearchRepository.search(any())
                        } returns listOf(
                            linemanagerSearchResult(cursorId = 1),
                            linemanagerSearchResult(cursorId = 2, employeeFnr = "12345678911"),
                        )
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                LinemanagerSearchRequest(
                                    orgNumber = narmesteLederRelasjon.orgNumber,
                                    pageSize = 1,
                                ),
                            )
                            bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                        }

                        response.status shouldBe HttpStatusCode.OK
                        val body = response.body<LinemanagerReadCollection>()
                        body.linemanagers.shouldHaveSize(1)
                        body.linemanagers.single().manager.email shouldBe "kari@example.com"
                        body.meta.size shouldBe 1
                        body.meta.pageSize shouldBe 1
                        body.meta.hasMore shouldBe true
                        body.meta.nextPageToken shouldBe "djE6MQ"
                    }
                }

                it("uses pageToken from the request when querying the next page") {
                    withTestApplication {
                        coEvery {
                            linemanagerSearchRepository.search(any())
                        } returns listOf(linemanagerSearchResult(cursorId = 2))
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                LinemanagerSearchRequest(
                                    orgNumber = narmesteLederRelasjon.orgNumber,
                                    pageToken = "djE6MQ",
                                ),
                            )
                            bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                        }

                        response.status shouldBe HttpStatusCode.OK
                        coVerify(exactly = 1) {
                            linemanagerSearchRepository.search(
                                match {
                                    it.orgNumber == narmesteLederRelasjon.orgNumber &&
                                        it.pageSize == LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE &&
                                        it.cursor == LinemanagerSearchCursor(id = 1)
                                },
                            )
                        }
                    }
                }

                it("returns linemanager results for authorized TokenX principals") {
                    withTestApplication {
                        val callerPid = "11223344556"
                        coEvery {
                            linemanagerSearchRepository.search(any())
                        } returns listOf(linemanagerSearchResult(cursorId = 1))
                        texasHttpClientMock.defaultMocks(
                            acr = "Level4",
                            pid = callerPid,
                        )
                        fakeAltinnTilgangerClient.addAccess(callerPid, narmesteLederRelasjon.orgNumber.value)

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                LinemanagerSearchRequest(
                                    orgNumber = narmesteLederRelasjon.orgNumber,
                                ),
                            )
                            bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                        }

                        response.status shouldBe HttpStatusCode.OK
                        val body = response.body<LinemanagerReadCollection>()
                        body.linemanagers.single().manager.nationalIdentificationNumber shouldBe PersonalIdentificationNumber("10987654321")
                    }
                }

                it("returns 400 for invalid orgNumber in request body") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """
                                {
                                  "orgNumber": "12345678",
                                  "pageSize": 1
                                }
                                """.trimIndent(),
                            )
                            bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                        }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    }
                }

                it("returns 400 for invalid managerNationalIdentificationNumber in request body") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """
                                {
                                  "orgNumber": "${narmesteLederRelasjon.orgNumber.value}",
                                  "managerNationalIdentificationNumber": "1098765432"
                                }
                                """.trimIndent(),
                            )
                            bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                        }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    }
                }

                it("returns 400 when unsupported text search is requested") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                LinemanagerSearchRequest(
                                    orgNumber = narmesteLederRelasjon.orgNumber,
                                    text = "Kari Nordmann",
                                ),
                            )
                            bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                        }

                        response.status shouldBe HttpStatusCode.BadRequest
                        val body = response.body<ApiError>()
                        body.type shouldBe ErrorType.BAD_REQUEST
                        body.message shouldBe "Text search is not supported for this endpoint"
                    }
                }

                it("returns 400 for invalid pageToken in request body") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """
                                {
                                  "orgNumber": "${narmesteLederRelasjon.orgNumber.value}",
                                  "pageToken": "not-a-valid-token"
                                }
                                """.trimIndent(),
                            )
                            bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                        }

                        response.status shouldBe HttpStatusCode.BadRequest
                        val body = response.body<ApiError>()
                        body.type shouldBe ErrorType.INVALID_FORMAT
                        body.message shouldBe "Invalid pageToken"
                        coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                    }
                }

                it("returns access error before unsupported text search for unauthorized principals") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(ID = "0192:000000000"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns false

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                LinemanagerSearchRequest(
                                    orgNumber = narmesteLederRelasjon.orgNumber,
                                    text = "Kari Nordmann",
                                ),
                            )
                            bearerAuth(createMockToken("000000000"))
                        }

                        response.status shouldBe HttpStatusCode.Forbidden
                        val body = response.body<ApiError>()
                        body.type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                        body.message shouldNotBe "Text search is not supported for this endpoint"
                        coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                    }
                }

                it("does not query repository when Maskinporten principal lacks org access") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(ID = "0192:000000000"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns false

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                LinemanagerSearchRequest(
                                    orgNumber = narmesteLederRelasjon.orgNumber,
                                ),
                            )
                            bearerAuth(createMockToken("000000000"))
                        }

                        response.status shouldBe HttpStatusCode.Forbidden
                        response.body<ApiError>().type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                        coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                    }
                }

                it("does not query repository when TokenX principal lacks org access") {
                    withTestApplication {
                        val callerPid = "11223344556"
                        texasHttpClientMock.defaultMocks(
                            acr = "Level4",
                            pid = callerPid,
                        )

                        val response = client.post("$API_V1_PATH$LINEMANAGER_API_PATH/search") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                LinemanagerSearchRequest(
                                    orgNumber = narmesteLederRelasjon.orgNumber,
                                ),
                            )
                            bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                        }

                        response.status shouldBe HttpStatusCode.Forbidden
                        coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                    }
                }
            }
        }
    })
