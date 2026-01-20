package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.AaregClient
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.FakeDialogportenClient
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.dialogporten.task.DeleteDialogTask
import no.nav.syfo.altinn.dialogporten.task.ResendDialogTask
import no.nav.syfo.altinn.dialogporten.task.SendDialogTask
import no.nav.syfo.altinn.dialogporten.task.UpdateDialogTask
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.client.PdpClient
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.AltinnTilgangerClient
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
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
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.DinesykmeldteClient
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.EregClient
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.kafka.SykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.INlResponseKafkaMessage
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.db.ISykmeldingDb
import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.service.SykmeldingService
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import kotlin.time.Duration

fun Application.configureDependencies() {
    install(Koin) {
        slf4jLogger()

        modules(
            applicationStateModule(),
            environmentModule(isLocalEnv()),
            databaseModule(),
            clientsModule(),
            servicesModule(),
            handlerModule()
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
    single<INarmestelederDb> {
        NarmestelederDb(get(), Dispatchers.IO)
    }
    single<ISykmeldingDb> {
        SykmeldingDb(get(), Dispatchers.IO)
    }
}

private fun handlerModule() = module {
    single { NlBehovLeesahHandler(get()) }
    single { SendtSykmeldingHandler(get(), get()) }
    single {
        LinemanagerRequirementRESTHandler(get(), get(), get(), get())
    }
}

private fun clientsModule() = module {
    single { httpClientDefault() }
    single { TexasHttpClient(client = get(), environment = env().texas) }
    single {
        if (isLocalEnv()) {
            FakeAaregClient()
        } else {
            AaregClient(
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
            DinesykmeldteClient(
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
            PdlClient(
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
            AltinnTilgangerClient(
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
            DialogportenClient(
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
            EregClient(
                eregBaseUrl = env().clientProperties.eregBaseUrl,
            )
        }
    }

    single {
        if (isLocalEnv()) {
            FakePdpClient()
        } else {
            PdpClient(
                httpClient = get(),
                baseUrl = env().clientProperties.altinn3BaseUrl,
                subscriptionKey = env().clientProperties.pdpSubscriptionKey,
                altinnTokenProvider = get(),
            )
        }
    }
}

private fun servicesModule() = module {
    single { AaregService(arbeidsforholdOversiktClient = get()) }
    single { DinesykmeldteService(dinesykmeldteClient = get()) }
    single { SykmeldingService(sykmeldingDb = get()) }
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
        AltinnTokenProvider(
            texasHttpClient = get(),
            altinnBaseUrl = env().clientProperties.altinn3BaseUrl,
            httpClient = get()
        )
    }
    single { PdlService(get()) }
    single { AltinnTilgangerService(get()) }
    single { LeaderElection(get(), env().otherProperties.electorPath) }
    single {
        val sykemeldingNLKafkaProducer = SykemeldingNLKafkaProducer(
            KafkaProducer<String, INlResponseKafkaMessage>(
                producerProperties(env().kafka, JacksonKafkaSerializer::class, StringSerializer::class)
            )
        )
        NarmestelederKafkaService(sykemeldingNLKafkaProducer)
    }
    single { PdpService(get()) }
    single {
        ValidationService(
            pdlService = get(),
            aaregService = get(),
            altinnTilgangerService = get(),
            dinesykmeldteService = get(),
            pdpService = get(),
            eregService = get()
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
            eregClient = get()
        )
    }
    single { SendDialogTask(get(), get()) }
    single {
        val pollingInterval = Duration.parse(env().otherProperties.updateDialogportenTaskProperties.pollingDelay)
        UpdateDialogTask(get(), get(), pollingInterval)
    }
    single {
        val pollingInterval = Duration.parse(env().otherProperties.updateDialogportenTaskProperties.pollingDelay)
        DeleteDialogTask(get(), get(), pollingInterval)
    }
    single {
        val pollingInterval = Duration.parse(env().otherProperties.updateDialogportenTaskProperties.pollingDelay)
        ResendDialogTask(get(), get(), pollingInterval)
    }
}

private fun Scope.env() = get<Environment>()
