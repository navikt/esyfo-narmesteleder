package no.nav.syfo.narmesteleder.api.v1

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.jackson.jackson
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import linemanager
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.IDinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.narmesteleder.api.internal.registerInternalApi
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.domain.LinemanagerManagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerPersonRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchCursor
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchResult
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.IEmployeeLinemanagerRepository
import no.nav.syfo.narmesteleder.exposed.ILinemanagerSearchRepository
import no.nav.syfo.narmesteleder.exposed.ILinemanagerStatisticsRepository
import no.nav.syfo.narmesteleder.kafka.FakeSykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.service.EmployeeLinemanagerService
import no.nav.syfo.narmesteleder.service.LinemanagerRevokeService
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.narmesteleder.service.LinemanagerStatisticsService
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.client.TexasHttpClient
import java.time.Instant
import java.util.UUID

abstract class LinemanagerApiV1TestBase(
    body: LinemanagerApiV1TestBase.() -> Unit,
) : DescribeSpec({}) {
    internal val pdlCacheMock = mockk<PdlCache>(relaxed = true)
    internal val pdlService = spyk(PdlService(FakePdlClient(), pdlCacheMock))
    internal val texasHttpClientMock = mockk<TexasHttpClient>()
    internal val narmestelederLookupService = mockk<NarmestelederLookupService>()
    internal val narmesteLederRelasjon = linemanager()
    internal val fakeAaregClient = FakeAaregClient()
    internal val aaregService = AaregService(fakeAaregClient)
    internal val fakeEregClient = FakeEregClient()
    internal val eregCache = mockk<EregCache>(relaxed = true)
    internal val eregService = EregService(fakeEregClient, eregCache)
    internal val narmestelederKafkaService =
        NarmestelederKafkaService(FakeSykmeldingNLKafkaProducer())
    internal val narmestelederKafkaServiceSpy = spyk(narmestelederKafkaService)
    internal val fakeAltinnTilgangerClient = FakeAltinnTilgangerClient()
    internal val altinnTilgangerServiceMock = AltinnTilgangerService(fakeAltinnTilgangerClient)
    internal val altinnAccessServiceSpy = spyk(altinnTilgangerServiceMock)
    internal val fakeDinesykmeldteClient = FakeDinesykmeldteClient()
    internal val dineSykmelteService: IDinesykmeldteService = DinesykmeldteService(fakeDinesykmeldteClient)
    internal val pdpService = mockk<PdpService>(relaxed = true)
    internal val principalAccessValidator = PrincipalAccessValidator(
        altinnTilgangerService = altinnAccessServiceSpy,
        pdpService = pdpService,
        eregService = eregService,
    )
    internal val sickLeaveValidator = SickLeaveValidator(
        dinesykmeldteService = dineSykmelteService,
    )
    internal val validationService =
        ValidationService(
            pdlService = pdlService,
            aaregService = aaregService,
            principalAccessValidator = principalAccessValidator,
            sickLeaveValidator = sickLeaveValidator,
        )
    internal val validationServiceSpy = spyk(validationService)
    internal val tokenXIssuer = "https://tokenx.nav.no"

    internal lateinit var fakeRepo: FakeNarmestelederDb
    internal lateinit var linemanagerSearchRepository: ILinemanagerSearchRepository
    internal lateinit var linemanagerStatisticsRepository: ILinemanagerStatisticsRepository
    internal lateinit var employeeLinemanagerRepository: IEmployeeLinemanagerRepository
    internal lateinit var narmesteLederService: NarmestelederService
    internal lateinit var nlBehovHandler: LinemanagerRequirementRESTHandler
    internal lateinit var linemanagerSearchService: LinemanagerSearchService
    internal lateinit var linemanagerStatisticsService: LinemanagerStatisticsService
    internal lateinit var employeeLinemanagerService: EmployeeLinemanagerService
    internal lateinit var linemanagerRevokeService: LinemanagerRevokeService

    init {
        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            fakeAltinnTilgangerClient.accessPolicy.clear()
            fakeAaregClient.arbeidsForholdForIdent.clear()
            fakeRepo = spyk(FakeNarmestelederDb())
            linemanagerSearchRepository = mockk()
            linemanagerStatisticsRepository = mockk()
            employeeLinemanagerRepository = mockk()
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
            linemanagerStatisticsService =
                LinemanagerStatisticsService(
                    validationService = validationServiceSpy,
                    linemanagerStatisticsRepository = linemanagerStatisticsRepository,
                )
            employeeLinemanagerService =
                EmployeeLinemanagerService(employeeLinemanagerRepository)
            linemanagerRevokeService =
                LinemanagerRevokeService(
                    narmestelederRevokeDb = mockk(),
                    narmestelederKafkaService = narmestelederKafkaServiceSpy,
                )
            coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns true
            fakeRepo.clear()
        }
        body()
    }

    internal fun withTestApplication(fn: suspend ApplicationTestBuilder.() -> Unit) {
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
                        altinnAccessServiceSpy,
                    )
                    registerInternalApi(
                        narmestelederLookupService,
                        texasHttpClientMock,
                        emptySet(),
                        linemanagerSearchService,
                        linemanagerStatisticsService,
                        employeeLinemanagerService,
                        linemanagerRevokeService,
                    )
                }
            }
            fn(this)
        }
    }

    internal fun linemanagerSearchResult(
        cursorId: Int,
        employeeFirstName: String = "Ola",
        employeeLastName: String = "Nordmann",
        orgNumber: OrganizationNumber = narmesteLederRelasjon.orgNumber,
        employeeFnr: String = "12345678910",
        managerFnr: String = "10987654321",
    ) = LinemanagerSearchResult(
        cursor = LinemanagerSearchCursor(
            firstName = employeeFirstName.lowercase(),
            lastName = employeeLastName.lowercase(),
            id = cursorId,
        ),
        linemanager = LinemanagerRead(
            id = UUID(0, cursorId.toLong()),
            orgNumber = orgNumber,
            activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
            employee = LinemanagerPersonRead(
                nationalIdentificationNumber = PersonalIdentificationNumber(employeeFnr),
                name = Name(
                    firstName = employeeFirstName,
                    middleName = null,
                    lastName = employeeLastName,
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
}
