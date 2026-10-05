package no.nav.syfo.narmestelederrelasjon

import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.EmployeeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.EmploymentLookup
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.ListActiveNarmesteledereForEmployeeUseCase
import no.nav.syfo.narmestelederrelasjon.application.LookupActiveNarmestelederUseCase
import no.nav.syfo.narmestelederrelasjon.application.NameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.RevokeActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonFromSendtSykmelding
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonFromSendtSykmeldingUseCase
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.AaregEmploymentLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.CachedPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.EregNarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedEmployeeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerNameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.PdlPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.ValkeyPersonDetailsCache
import org.koin.dsl.module
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

fun narmestelederrelasjonModule() = module {
    single<PublishNarmestelederrelasjon> { KafkaPublishNarmestelederrelasjon(get()) }
    single<PublishNarmestelederrelasjonRevocation> { KafkaPublishNarmestelederrelasjonRevocation(get()) }
    single<NarmestelederrelasjonRepository> { ExposedNarmestelederrelasjonRepository(get()) }
    single<ActiveNarmestelederrelasjonRepository> { ExposedActiveNarmestelederrelasjonRepository(get<ExposedDatabase>()) }
    single { LookupActiveNarmestelederUseCase(get()) }
    single<EmployeeNarmestelederrelasjonRepository> { ExposedEmployeeNarmestelederrelasjonRepository(get<ExposedDatabase>()) }
    single { ListActiveNarmesteledereForEmployeeUseCase(get()) }
    single<ActiveSykmeldingLookup> { DinesykmeldteActiveSykmeldingLookup(get()) }
    single<EmploymentLookup> { AaregEmploymentLookup(get()) }
    single<PersonLookup> { CachedPersonLookup(PdlPersonLookup(get()), ValkeyPersonDetailsCache(get())) }
    single<NameValidationMetrics> { MicrometerNameValidationMetrics() }
    single<EstablishNarmestelederrelasjon> { EstablishNarmestelederrelasjonUseCase(get(), get(), get(), get(), get()) }
    single { SubmitNarmestelederrelasjonUseCase(get(), get()) }
    single<NarmestelederrelasjonOrganization> { EregNarmestelederrelasjonOrganization(get()) }
    single<GetNarmestelederrelasjonUseCase> { GetNarmestelederrelasjonUseCase(get(), get(), get(), get()) }
    single<RevokeNarmestelederrelasjonUseCase> { RevokeNarmestelederrelasjonUseCase(get(), get(), get()) }
    single { RevokeActiveNarmestelederrelasjonUseCase(get(), get(), get(), get(), get()) }
    single<RevokeNarmestelederrelasjonFromSendtSykmelding> { RevokeNarmestelederrelasjonFromSendtSykmeldingUseCase(get()) }
}
