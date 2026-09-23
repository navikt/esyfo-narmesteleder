package no.nav.syfo.narmestelederrelasjon

import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaEstablishNarmestelederrelasjon
import org.koin.dsl.module

fun narmestelederrelasjonModule() = module {
    single<EstablishNarmestelederrelasjon> { KafkaEstablishNarmestelederrelasjon(get()) }
}
