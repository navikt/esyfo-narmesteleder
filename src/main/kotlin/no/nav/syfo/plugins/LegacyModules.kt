package no.nav.syfo.plugins

import kotlinx.coroutines.Dispatchers
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.kafka.PdlLeesahNameUpdateService
import no.nav.syfo.person.service.PersonEnrichmentService
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
import org.koin.dsl.module

// Registrations for code that has not yet moved into a capability module (ADR-0002).
internal fun legacyRepositoriesModule() = module {
    single<NarmestelederDb> { PostgresNarmestelederDb(database = get(), dispatcher = Dispatchers.IO) }
    single<SykmeldingDb> { PostgresSykmeldingDb(database = get(), dispatcher = Dispatchers.IO) }
    single<ActiveSykmeldingRepository> { SendtSykmeldingRepository(database = get()) }
    single<SendtSykmeldingNarmestelederBruddRepository> {
        PostgresSendtSykmeldingNarmestelederBruddRepository(database = get())
    }
    single<SykmeldingRetentionRepository> { ExposedSykmeldingRetentionRepository(database = get()) }
}

internal fun legacyServicesModule() = module {
    single { PdlService(pdlClient = get()) }
    single { PdlLeesahNameUpdateService(database = get(), pdlService = get()) }
    single { PersonEnrichmentService(database = get(), pdlService = get()) }
    single {
        DialogportenService(
            dialogportenClient = get(),
            narmestelederDb = get(),
            otherEnvironmentProperties = env().otherProperties,
            pdlService = get(),
        )
    }

    single { SykmeldingService(sykmeldingDb = get(), clock = get()) }
    single { SykmeldingRetentionMetrics() }
    single { DeleteOldSykmeldinger(repository = get(), clock = get(), metrics = get()) }
    single { NarmestelederBruddService(revokeNarmestelederrelasjon = get(), bruddRepository = get()) }
    single {
        SendtSykmeldingHandler(
            createNarmestelederbehov = get(),
            sykmeldingService = get(),
            narmestelederBruddService = get(),
        )
    }

    single { NlBehovLeesahHandler(createNarmestelederbehov = get()) }
}
