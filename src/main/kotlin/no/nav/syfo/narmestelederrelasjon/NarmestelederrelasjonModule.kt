package no.nav.syfo.narmestelederrelasjon

import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.EregNarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaEstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjonRevocation
import org.koin.dsl.module

fun narmestelederrelasjonModule() = module {
    single<EstablishNarmestelederrelasjon> { KafkaEstablishNarmestelederrelasjon(get()) }
    single<PublishNarmestelederrelasjonRevocation> { KafkaPublishNarmestelederrelasjonRevocation(get()) }
    single<NarmestelederrelasjonRepository> { ExposedNarmestelederrelasjonRepository(get()) }
    single<ActiveSykmeldingLookup> { DinesykmeldteActiveSykmeldingLookup(get()) }
    single<NarmestelederrelasjonOrganization> { EregNarmestelederrelasjonOrganization(get()) }
    single<GetNarmestelederrelasjon> { GetNarmestelederrelasjon(get(), get(), get(), get()) }
    single<RevokeNarmestelederrelasjon> { RevokeNarmestelederrelasjon(get(), get(), get()) }
}
