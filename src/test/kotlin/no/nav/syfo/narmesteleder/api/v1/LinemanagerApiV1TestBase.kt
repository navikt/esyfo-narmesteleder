package no.nav.syfo.narmesteleder.api.v1

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.jackson.jackson
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import linemanager
import no.nav.syfo.TestDB
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.dialogporten.client.FakeDialogportenClient
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.altinntilganger.registerAccessOrganizationsApi
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.dinesykmeldte.ClientDinesykmeldteService
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.exposed.LinemanagerStatisticsRepository
import no.nav.syfo.narmesteleder.service.LinemanagerStatisticsService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovRepository
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.AaregEmploymentLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerNameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.PdlPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.FakeSykmeldingNarmestelederProducer
import no.nav.syfo.organisasjonstilgang.infrastructure.AltinnOrganizationAccess
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.texas.client.TexasHttpClient

abstract class LinemanagerApiV1TestBase(
    body: LinemanagerApiV1TestBase.() -> Unit,
) : DescribeSpec({}) {
    internal val pdlService = spyk(PdlService(FakePdlClient()))
    internal val texasHttpClientMock = mockk<TexasHttpClient>()
    internal val narmesteLederRelasjon = linemanager()
    internal val fakeAaregClient = FakeAaregClient()
    internal val aaregService = AaregService(fakeAaregClient)
    internal val fakeEregClient = FakeEregClient()
    internal val eregCache = mockk<EregCache>(relaxed = true)
    internal val eregService = EregService(fakeEregClient, eregCache)
    internal val relationProducerSpy = spyk(FakeSykmeldingNarmestelederProducer())
    internal val fakeAltinnTilgangerClient = FakeAltinnTilgangerClient()
    internal val altinnTilgangerServiceMock = AltinnTilgangerService(fakeAltinnTilgangerClient)
    internal val altinnAccessServiceSpy = spyk(altinnTilgangerServiceMock)
    internal val fakeDinesykmeldteClient = FakeDinesykmeldteClient()
    internal val dineSykmelteService: DinesykmeldteService = spyk(ClientDinesykmeldteService(fakeDinesykmeldteClient))
    internal val pdpService = mockk<PdpService>(relaxed = true)
    internal val principalAccessValidator = PrincipalAccessValidator(
        altinnTilgangerService = altinnAccessServiceSpy,
        pdpService = pdpService,
        eregService = eregService,
    )
    internal val validationService =
        ValidationService(
            principalAccessValidator = principalAccessValidator,
        )
    internal val validationServiceSpy = spyk(validationService)
    internal val tokenXIssuer = "https://tokenx.nav.no"

    internal lateinit var fakeRepo: FakeNarmestelederDb
    internal lateinit var linemanagerStatisticsRepository: LinemanagerStatisticsRepository
    internal lateinit var narmesteLederService: NarmestelederService
    internal lateinit var nlBehovHandler: LinemanagerRequirementRESTHandler
    internal lateinit var fulfillNarmestelederbehov: FulfillNarmestelederbehovUseCase
    internal lateinit var linemanagerStatisticsService: LinemanagerStatisticsService

    init {
        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            fakeAltinnTilgangerClient.accessPolicy.clear()
            fakeAaregClient.arbeidsForholdForIdent.clear()
            fakeRepo = spyk(FakeNarmestelederDb())
            linemanagerStatisticsRepository = mockk()
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
                )
            fulfillNarmestelederbehov = FulfillNarmestelederbehovUseCase(
                ExposedNarmestelederbehovRepository(TestDB.exposedDatabase),
                AltinnOrganizationAccess(altinnTilgangerServiceMock, pdpService, eregService),
                EstablishNarmestelederrelasjonUseCase(
                    DinesykmeldteActiveSykmeldingLookup(dineSykmelteService),
                    AaregEmploymentLookup(aaregService),
                    PdlPersonLookup(FakePdlClient()),
                    MicrometerNameValidationMetrics(),
                    KafkaPublishNarmestelederrelasjon(relationProducerSpy),
                ),
                DialogportenNarmestelederbehovDialog(FakeDialogportenClient()),
            )
            linemanagerStatisticsService =
                LinemanagerStatisticsService(
                    validationService = validationServiceSpy,
                    linemanagerStatisticsRepository = linemanagerStatisticsRepository,
                )
            coEvery { pdpService.accessDecisionForResource(any(), any(), any()) } returns Decision.Permit
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
                    route(API_V1_PATH) {
                        install(AddTokenIssuerPlugin)
                        registerLinemanagerApiV1(
                            texasHttpClientMock,
                            nlBehovHandler,
                            fulfillNarmestelederbehov,
                        )
                        registerAccessOrganizationsApi(altinnAccessServiceSpy, texasHttpClientMock)
                    }
                    route(INTERNAL_API_V1_PATH) {
                        install(AddTokenIssuerPlugin)
                        registerLinemanagerStatisticsApi(texasHttpClientMock, linemanagerStatisticsService)
                    }
                }
            }
            fn(this)
        }
    }
}
