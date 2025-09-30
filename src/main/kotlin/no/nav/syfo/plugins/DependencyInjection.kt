package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.AaregClient
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.AltinnTilgangerClient
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.Environment
import no.nav.syfo.application.LocalEnvironment
import no.nav.syfo.application.NaisEnvironment
import no.nav.syfo.application.database.Database
import no.nav.syfo.application.database.DatabaseConfig
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.isLocalEnv
import no.nav.syfo.application.kafka.JacksonKafkaSerializer
import no.nav.syfo.application.kafka.producerProperties
import no.nav.syfo.narmesteleder.kafka.FakeSykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.SykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.INlResponseKafkaMessage
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureDependencies() {
    install(Koin) {
        slf4jLogger()

        modules(
            applicationStateModule(),
            environmentModule(isLocalEnv()),
            httpClient(),
            databaseModule(),
            servicesModule()
        )
    }
}

private fun applicationStateModule() = module { single { ApplicationState() } }

private fun environmentModule(isLocalEnv: Boolean) = module {
    single {
        if (isLocalEnv) LocalEnvironment()
        else NaisEnvironment()
    }
}

private fun httpClient() = module {
    single {
        httpClientDefault()
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
}

private fun servicesModule() = module {
    single { TexasHttpClient(client = get(), environment = env().texas) }
    single {
        if (isLocalEnv()) FakeAaregClient() else AaregClient(
            aaregBaseUrl = env().clientProperties.aaregBaseUrl,
            texasHttpClient = get(),
            scope = env().clientProperties.aaregScope,
        )
    }
    single {
        AaregService(
            arbeidsforholdOversiktClient = get()
        )
    }
    single {
        if (isLocalEnv()) FakePdlClient() else PdlClient(
            httpClient = get(),
            pdlBaseUrl = env().clientProperties.pdlBaseUrl,
            texasHttpClient = get(),
            scope = env().clientProperties.pdlScope
        )
    }
    single {
        PdlService(get())
    }
    single {
        if (isLocalEnv()) FakeAltinnTilgangerClient() else AltinnTilgangerClient(
            texasClient = get(),
            httpClient = get(),
            baseUrl = env().clientProperties.altinnTilgangerBaseUrl,
        )
    }

    single { AltinnTilgangerService(get()) }
    single {
        val sykemeldingNLKafkaProducer = if (isLocalEnv()) SykemeldingNLKafkaProducer(
            KafkaProducer<String, INlResponseKafkaMessage>(
                producerProperties(env().kafka, JacksonKafkaSerializer::class, StringSerializer::class)
            )
        ) else FakeSykemeldingNLKafkaProducer()
        NarmestelederKafkaService(sykemeldingNLKafkaProducer, get(), get())
    }
}

private fun Scope.env() = get<Environment>()
