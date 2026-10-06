package no.nav.syfo.narmestelederstatistikk

import no.nav.syfo.narmestelederstatistikk.application.GetNarmestelederstatistikkUseCase
import no.nav.syfo.narmestelederstatistikk.application.NarmestelederstatistikkRepository
import no.nav.syfo.narmestelederstatistikk.infrastructure.ExposedNarmestelederstatistikkRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

fun narmestelederstatistikkModule() = module {
    single<NarmestelederstatistikkRepository> { ExposedNarmestelederstatistikkRepository(get<Database>()) }
    single { GetNarmestelederstatistikkUseCase(get(), get()) }
}
