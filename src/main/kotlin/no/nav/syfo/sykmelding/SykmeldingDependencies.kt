package no.nav.syfo.sykmelding

import no.nav.syfo.sykmelding.application.NarmestelederBruddService
import no.nav.syfo.sykmelding.application.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.application.SendtSykmeldingNarmestelederBruddRepository
import no.nav.syfo.sykmelding.application.SykmeldingDb
import no.nav.syfo.sykmelding.application.SykmeldingService
import no.nav.syfo.sykmelding.infrastructure.ExposedSykmeldingDb
import no.nav.syfo.sykmelding.infrastructure.PostgresSendtSykmeldingNarmestelederBruddRepository
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionMetrics
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionRepository
import no.nav.syfo.sykmelding.retention.infrastructure.ExposedSykmeldingRetentionRepository
import org.koin.dsl.module

fun sykmeldingDependencies() = module {
    single<SykmeldingDb> { ExposedSykmeldingDb(database = get()) }
    single<SendtSykmeldingNarmestelederBruddRepository> {
        PostgresSendtSykmeldingNarmestelederBruddRepository(database = get())
    }
    single<SykmeldingRetentionRepository> { ExposedSykmeldingRetentionRepository(database = get()) }

    single { SykmeldingService(sykmeldingDb = get(), clock = get()) }
    single { SykmeldingRetentionMetrics() }
    single { DeleteOldSykmeldinger(repository = get(), clock = get(), metrics = get()) }
    single { NarmestelederBruddService(revokeNarmestelederrelasjon = get(), bruddRepository = get()) }
    single {
        SendtSykmeldingHandler(
            createNarmestelederbehov = get(),
            sykmeldingService = get(),
            narmestelederBruddService = get(),
            hasActiveNarmestelederrelasjon = get(),
        )
    }
}
