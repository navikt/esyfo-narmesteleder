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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
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
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import linemanager
import linemanagerRevoke
import manager
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.TestDB
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.IDinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.kafka.FakeSykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.outbox.OutboxDirectSender
import no.nav.syfo.outbox.OutboxDispatcher
import no.nav.syfo.outbox.OutboxEventRepository
import no.nav.syfo.outbox.OutboxEventStatus
import no.nav.syfo.outbox.OutboxMetrics
import no.nav.syfo.outbox.SykmeldingNlAvbruttOutboxHandler
import no.nav.syfo.outbox.SykmeldingNlRelasjonOutboxHandler
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient
import prepareGetPersonResponse
import java.util.UUID

class LinemanagerApiOutboxTest :
    DescribeSpec({
        val pdlCacheMock = mockk<PdlCache>(relaxed = true)
        val pdlService = spyk(PdlService(FakePdlClient(), pdlCacheMock))
        val texasHttpClientMock = mockk<TexasHttpClient>()
        val fakeAaregClient = FakeAaregClient()
        val aaregService = AaregService(fakeAaregClient)
        val fakeAltinnTilgangerClient = FakeAltinnTilgangerClient()
        val altinnTilgangerService = AltinnTilgangerService(fakeAltinnTilgangerClient)
        val fakeDinesykmeldteClient = FakeDinesykmeldteClient()
        val dinesykmeldteService: IDinesykmeldteService = DinesykmeldteService(fakeDinesykmeldteClient)
        val pdpService = mockk<PdpService>(relaxed = true)
        val eregService = EregService(FakeEregClient(), mockk<EregCache>(relaxed = true))
        val principalAccessValidator = PrincipalAccessValidator(
            altinnTilgangerService = altinnTilgangerService,
            pdpService = pdpService,
            eregService = eregService,
        )
        val validationService = ValidationService(
            pdlService = pdlService,
            aaregService = aaregService,
            principalAccessValidator = principalAccessValidator,
            sickLeaveValidator = SickLeaveValidator(dinesykmeldteService),
        )
        val objectMapper = jacksonMapper()
        val outboxRepository = OutboxEventRepository(TestDB.exposedDatabase)
        val fakeProducer = FakeSykmeldingNLKafkaProducer()
        val outboxDispatcher = OutboxDispatcher(
            handlers = listOf(
                SykmeldingNlRelasjonOutboxHandler(fakeProducer, objectMapper),
                SykmeldingNlAvbruttOutboxHandler(fakeProducer, objectMapper),
            ),
        )
        val outboxDirectSender = OutboxDirectSender(outboxRepository, outboxDispatcher, OutboxMetrics())
        val narmestelederKafkaService = NarmestelederKafkaService(
            outboxEventRepository = outboxRepository,
            outboxDirectSender = outboxDirectSender,
            database = TestDB.exposedDatabase,
            objectMapper = objectMapper,
        )
        val narmestelederDb = NarmestelederDb(TestDB.database, Dispatchers.IO)
        lateinit var narmestelederService: NarmestelederService
        lateinit var nlBehovHandler: LinemanagerRequirementRESTHandler

        fun withTestApplication(fn: suspend ApplicationTestBuilder.() -> Unit) {
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
                            narmestelederKafkaService,
                            texasHttpClientMock,
                            validationService,
                            nlBehovHandler,
                        )
                    }
                }
                fn(this)
            }
        }

        suspend fun seedRequirement(): UUID {
            val entity = narmestelederDb.insertNlBehov(
                NarmestelederBehovEntity(
                    orgnummer = "123456789",
                    hovedenhetOrgnummer = "123456789",
                    sykmeldtFnr = "12345678910",
                    behovReason = BehovReason.NY_LEDER,
                    behovStatus = BehovStatus.BEHOV_CREATED,
                    etternavn = "Sykmeldt",
                ),
            )
            return entity.id ?: error("Expected requirement id")
        }

        beforeTest {
            clearAllMocks()
            TestDB.clearAllData()
            fakeAaregClient.arbeidsForholdForIdent.clear()
            fakeAltinnTilgangerClient.accessPolicy.clear()
            fakeProducer.sentRelasjoner.clear()
            fakeProducer.sentBrudd.clear()
            fakeProducer.shouldFailRelasjon = false
            fakeProducer.shouldFailBrudd = false
            coEvery { pdlCacheMock.getPerson(any()) } returns null
            coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns true
            narmestelederService = NarmestelederService(
                nlDb = narmestelederDb,
                persistLeesahNlBehov = true,
                aaregService = aaregService,
                pdlService = pdlService,
                dinesykmeldteService = dinesykmeldteService,
                dialogportenService = mockk<DialogportenService>(relaxed = true),
            )
            nlBehovHandler = LinemanagerRequirementRESTHandler(
                narmesteLederService = narmestelederService,
                validationService = validationService,
                narmestelederKafkaService = narmestelederKafkaService,
            )
        }

        describe("outbox-backed API flow") {
            it("returns 202 for assign even when direct Kafka send fails") {
                val request = linemanager()
                fakeProducer.shouldFailRelasjon = true

                withTestApplication {
                    pdlService.prepareGetPersonResponse(request.manager)
                    pdlService.prepareGetPersonResponse(request.employeeIdentificationNumber, request.lastName)
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${request.orgNumber}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    fakeAaregClient.arbeidsForholdForIdent[request.employeeIdentificationNumber] =
                        listOf(request.orgNumber to request.orgNumber)
                    fakeAaregClient.arbeidsForholdForIdent[request.manager.nationalIdentificationNumber] =
                        listOf(request.orgNumber to request.orgNumber)

                    val response = client.post("/api/v1/linemanager") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                        bearerAuth(createMockToken(request.orgNumber))
                    }

                    response.status shouldBe HttpStatusCode.Accepted
                    outboxRepository.findAll().shouldHaveSize(1)
                    outboxRepository.findAll().single().status shouldBe OutboxEventStatus.RETRY
                }
            }

            it("returns 202 for revoke even when direct Kafka send fails") {
                val request = linemanagerRevoke()
                fakeProducer.shouldFailBrudd = true

                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${request.orgNumber}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(request.employeeIdentificationNumber, request.lastName)
                    fakeAaregClient.arbeidsForholdForIdent[request.employeeIdentificationNumber] =
                        listOf(request.orgNumber to request.orgNumber)

                    val response = client.post("$API_V1_PATH/$REVOKE_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                        bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                    }

                    response.status shouldBe HttpStatusCode.Accepted
                    outboxRepository.findAll().shouldHaveSize(1)
                    outboxRepository.findAll().single().status shouldBe OutboxEventStatus.RETRY
                }
            }

            it("returns 202 for fulfilled requirement even when direct Kafka send fails after commit") {
                val requirementId = seedRequirement()
                val manager = manager()
                fakeProducer.shouldFailRelasjon = true

                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:123456789"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(manager)
                    fakeAaregClient.arbeidsForholdForIdent["12345678910"] =
                        listOf("123456789" to "123456789")
                    fakeAaregClient.arbeidsForholdForIdent[manager.nationalIdentificationNumber] =
                        listOf("123456789" to "123456789")

                    val response = client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                        contentType(ContentType.Application.Json)
                        setBody(manager)
                        bearerAuth(createMockToken("123456789"))
                    }

                    response.status shouldBe HttpStatusCode.Accepted
                    narmestelederDb.findBehovById(requirementId)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
                    outboxRepository.findAll().shouldHaveSize(1)
                    outboxRepository.findAll().single().status shouldBe OutboxEventStatus.RETRY
                }
            }
        }
    })
