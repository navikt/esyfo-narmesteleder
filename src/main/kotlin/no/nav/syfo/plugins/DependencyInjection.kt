package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.aareg.client.HttpAaregClient
import no.nav.syfo.altinn.dialogporten.client.FakeDialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.dialogporten.task.SendDialogTask
import no.nav.syfo.altinn.dialogporten.task.UpdateDialogTask
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.client.HttpPdpClient
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.altinntilganger.client.HttpAltinnTilgangerClient
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.database.Database
import no.nav.syfo.application.database.DatabaseConfig
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.environment.LocalEnvironment
import no.nav.syfo.application.environment.NaisEnvironment
import no.nav.syfo.application.environment.isLocalEnv
import no.nav.syfo.application.kafka.JacksonKafkaSerializer
import no.nav.syfo.application.kafka.producerProperties
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.application.valkey.ValkeyCache
import no.nav.syfo.dinesykmeldte.ClientDinesykmeldteService
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.dinesykmeldte.client.HttpDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.ereg.client.HttpEregClient
import no.nav.syfo.maintenance.MaintenanceTask
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederLookupDb
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederLookupDb
import no.nav.syfo.narmesteleder.exposed.EmployeeLinemanagerRepository
import no.nav.syfo.narmesteleder.exposed.LinemanagerSearchRepository
import no.nav.syfo.narmesteleder.exposed.LinemanagerStatisticsRepository
import no.nav.syfo.narmesteleder.exposed.PostgresEmployeeLinemanagerRepository
import no.nav.syfo.narmesteleder.exposed.PostgresLinemanagerSearchRepository
import no.nav.syfo.narmesteleder.exposed.PostgresLinemanagerStatisticsRepository
import no.nav.syfo.narmesteleder.kafka.KafkaSykmeldingNarmestelederProducer
import no.nav.syfo.narmesteleder.kafka.NarmestelederLeesahProducer
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederResponseKafkaMessage
import no.nav.syfo.narmesteleder.service.EmployeeLinemanagerService
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.narmesteleder.service.LinemanagerStatisticsService
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.narmesteleder.service.NarmestelederRegisterService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.narmestelederbehov.narmestelederbehovModule
import no.nav.syfo.narmestelederrelasjon.narmestelederrelasjonModule
import no.nav.syfo.organisasjonstilgang.organisasjonstilgangModule
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.pdl.client.HttpPdlClient
import no.nav.syfo.pdl.kafka.PdlLeesahNameUpdateService
import no.nav.syfo.person.service.PersonEnrichmentService
import no.nav.syfo.person.task.PersonEnrichmentTask
import no.nav.syfo.sykmelding.db.PostgresSykmeldingDb
import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.exposed.ActiveSykmeldingRepository
import no.nav.syfo.sykmelding.exposed.PostgresSendtSykmeldingNarmestelederBruddRepository
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingNarmestelederBruddRepository
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingRepository
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionMetrics
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionRepository
import no.nav.syfo.sykmelding.retention.infrastructure.ExposedSykmeldingRetentionRepository
import no.nav.syfo.sykmelding.service.NarmestelederBruddService
import no.nav.syfo.sykmelding.service.SykmeldingService
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import no.nav.syfo.util.httpClientSSE
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.time.Clock
import kotlin.time.Duration
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

fun Application.configureDependencies() {
    install(Koin) {
        slf4jLogger()

        modules(
            applicationStateModule(),
            environmentModule(isLocalEnv()),
            databaseModule(),
            clientsModule(),
            valkeyModule(),
            servicesModule(),
            handlerModule(),
            tasksModule(),
            narmestelederbehovModule(),
            narmestelederrelasjonModule(),
            organisasjonstilgangModule(),
        )
    }
}

private fun applicationStateModule() = module { single { ApplicationState() } }

private fun environmentModule(isLocalEnv: Boolean) = module {
    single {
        if (isLocalEnv) {
            LocalEnvironment()
        } else {
            NaisEnvironment()
        }
    }
}

private fun databaseModule() = module {
    single<DatabaseInterface> {
        Database(
            DatabaseConfig(
                jdbcUrl = env().database.jdbcUrl(),
                username = env().database.username,
                password = env().database.password,
            )
        )
    }
    single<ExposedDatabase> {
        val db = get<DatabaseInterface>() as Database
        ExposedDatabase.connect(datasource = db.dataSource)
    }
    single<NarmestelederDb> {
        PostgresNarmestelederDb(get(), Dispatchers.IO)
    }
    single<NarmestelederLookupDb> {
        PostgresNarmestelederLookupDb(get<ExposedDatabase>(), Dispatchers.IO)
    }
    single<SykmeldingDb> {
        PostgresSykmeldingDb(get(), Dispatchers.IO)
    }
    single<ActiveSykmeldingRepository> {
        SendtSykmeldingRepository(get())
    }
    single<SendtSykmeldingNarmestelederBruddRepository> {
        PostgresSendtSykmeldingNarmestelederBruddRepository(get())
    }
    single<SykmeldingRetentionRepository> {
        ExposedSykmeldingRetentionRepository(get())
    }
    single<LinemanagerSearchRepository> {
        PostgresLinemanagerSearchRepository(get())
    }
    single<LinemanagerStatisticsRepository> {
        PostgresLinemanagerStatisticsRepository(get())
    }
    single<EmployeeLinemanagerRepository> { PostgresEmployeeLinemanagerRepository(get()) }
}

private fun handlerModule() = module {
    single { NarmestelederLookupService(get()) }
    single { NlBehovLeesahHandler(get()) }
    single { NarmestelederBruddService(get(), get()) }
    single { SendtSykmeldingHandler(get(), get(), get()) }
    single {
        LinemanagerRequirementRESTHandler(get(), get())
    }
}

private fun clientsModule() = module {
    single { httpClientDefault() }
    single { TexasHttpClient(client = get(), environment = env().texas) }
    single {
        if (isLocalEnv()) {
            FakeAaregClient()
        } else {
            HttpAaregClient(
                aaregBaseUrl = env().clientProperties.aaregBaseUrl,
                texasHttpClient = get(),
                scope = env().clientProperties.aaregScope,
            )
        }
    }
    single {
        if (isLocalEnv()) {
            FakeDinesykmeldteClient()
        } else {
            HttpDinesykmeldteClient(
                texasHttpClient = get(),
                scope = env().clientProperties.dinesykmeldteScope,
                httpClient = get(),
                dinesykmeldteBaseUrl = env().clientProperties.dinesykmeldteBaseUrl,
            )
        }
    }
    single {
        if (isLocalEnv()) {
            FakePdlClient()
        } else {
            HttpPdlClient(
                httpClient = get(),
                pdlBaseUrl = env().clientProperties.pdlBaseUrl,
                texasHttpClient = get(),
                scope = env().clientProperties.pdlScope
            )
        }
    }
    single {
        if (isLocalEnv()) {
            FakeAltinnTilgangerClient()
        } else {
            HttpAltinnTilgangerClient(
                texasClient = get(),
                httpClient = get(),
                baseUrl = env().clientProperties.altinnTilgangerBaseUrl,
            )
        }
    }

    single {
        if (isLocalEnv()) {
            FakeDialogportenClient()
        } else {
            HttpDialogportenClient(
                httpClient = get(),
                baseUrl = env().clientProperties.altinn3BaseUrl,
                altinnTokenProvider = get(),
            )
        }
    }

    single {
        if (isLocalEnv()) {
            FakeEregClient()
        } else {
            HttpEregClient(
                eregBaseUrl = env().clientProperties.eregBaseUrl,
            )
        }
    }

    single {
        if (isLocalEnv()) {
            FakePdpClient()
        } else {
            HttpPdpClient(
                httpClient = get(),
                baseUrl = env().clientProperties.altinn3BaseUrl,
                subscriptionKey = env().clientProperties.pdpSubscriptionKey,
                altinnTokenProvider = get(),
            )
        }
    }
}

private fun valkeyModule() = module {
    single {
        ValkeyCache(env().valkeyEnvironment)
    }
    single {
        PdlCache(get())
    }
    single {
        EregCache(get())
    }
}

private fun servicesModule() = module {
    single { Clock.systemDefaultZone() }
    single { AaregService(arbeidsforholdOversiktClient = get()) }
    single { ClientDinesykmeldteService(dinesykmeldteClient = get()) }
    single<DinesykmeldteService> {
        ClientDinesykmeldteService(get())
    }
    single { SykmeldingService(sykmeldingDb = get(), clock = get()) }
    single { SykmeldingRetentionMetrics() }
    single { DeleteOldSykmeldinger(repository = get(), clock = get(), metrics = get()) }
    single {
        NarmestelederService(
            nlDb = get(),
            persistLeesahNlBehov = env().otherProperties.persistLeesahNlBehov,
            aaregService = get(),
            pdlService = get(),
            dinesykmeldteService = get(),
            dialogportenService = get(),
        )
    }
    single {
        LinemanagerSearchService(
            validationService = get(),
            linemanagerSearchRepository = get(),
        )
    }
    single {
        LinemanagerStatisticsService(
            validationService = get(),
            linemanagerStatisticsRepository = get(),
        )
    }
    single { EmployeeLinemanagerService(repository = get()) }
    single { NarmestelederRegisterService(get()) }
    single {
        AltinnTokenProvider(
            texasHttpClient = get(),
            altinnBaseUrl = env().clientProperties.altinn3BaseUrl,
            httpClient = get()
        )
    }
    single { PdlService(get(), get()) }
    single { PdlLeesahNameUpdateService(get(), get()) }

    single { AltinnTilgangerService(get()) }
    single {
        LeaderChangeSSEListener(httpClientSSE(), env().otherProperties.electorSSEUrl, isLocalEnv())
    }
    single {
        LeaderElection(get(), env().otherProperties.electorPath)
    }
    single {
        val sykmeldingNLKafkaProducer = KafkaSykmeldingNarmestelederProducer(
            KafkaProducer<String, NarmestelederResponseKafkaMessage>(
                producerProperties(env().kafka, JacksonKafkaSerializer::class, StringSerializer::class)
            )
        )
        NarmestelederKafkaService(sykmeldingNLKafkaProducer)
    }
    single<SykmeldingNarmestelederProducer> { get<NarmestelederKafkaService>().kafkaSykemeldingProducer }
    single {
        NarmestelederLeesahProducer(
            KafkaProducer<String, String?>(
                producerProperties(env().kafka, StringSerializer::class, StringSerializer::class)
            )
        )
    }
    single { PdpService(get()) }
    single { PrincipalAccessValidator(get(), get(), get()) }
    single { SickLeaveValidator(get()) }
    single {
        ValidationService(
            pdlService = get(),
            aaregService = get(),
            principalAccessValidator = get(),
            sickLeaveValidator = get(),
        )
    }
    single {
        DialogportenService(
            dialogportenClient = get(),
            narmestelederDb = get(),
            otherEnvironmentProperties = env().otherProperties,
            pdlService = get(),
        )
    }
    single {
        EregService(
            eregClient = get(),
            eregCache = get()
        )
    }
    single { PersonEnrichmentService(database = get(), pdlService = get()) }
}

private fun tasksModule() = module {
    single {
        MaintenanceTask(
            narmestelederService = get(),
            deleteOldSykmeldinger = get(),
            env = env().otherProperties
        )
    }
    single { SendDialogTask(get()) }
    single {
        val pollingInterval = Duration.parse(env().otherProperties.updateDialogportenTaskProperties.pollingDelay)
        UpdateDialogTask(get(), pollingInterval)
    }
    single {
        val pollingInterval = Duration.parse(env().otherProperties.personEnrichmentTaskDelay)
        PersonEnrichmentTask(get(), pollingInterval)
    }
}

private fun Scope.env() = get<Environment>()
