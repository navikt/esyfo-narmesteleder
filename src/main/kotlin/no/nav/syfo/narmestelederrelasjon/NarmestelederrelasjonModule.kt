package no.nav.syfo.narmestelederrelasjon

import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.BulkPersonLookup
import no.nav.syfo.narmestelederrelasjon.application.DiscardedEmailAddressMetrics
import no.nav.syfo.narmestelederrelasjon.application.EmployeeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.EmploymentLookup
import no.nav.syfo.narmestelederrelasjon.application.EnrichPendingRelationPersonsUseCase
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.ListActiveNarmesteledereForEmployeeUseCase
import no.nav.syfo.narmestelederrelasjon.application.LookupActiveNarmestelederUseCase
import no.nav.syfo.narmestelederrelasjon.application.NameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederRegisterMetrics
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonSearchRepository
import no.nav.syfo.narmestelederrelasjon.application.PersistNarmestelederrelasjonerFromLeesahUseCase
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.RelationPersonNameUpdateMetrics
import no.nav.syfo.narmestelederrelasjon.application.RelationPersonRepository
import no.nav.syfo.narmestelederrelasjon.application.RevokeActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonFromSendtSykmelding
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonFromSendtSykmeldingUseCase
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.SearchActiveNarmestelederrelasjonerUseCase
import no.nav.syfo.narmestelederrelasjon.application.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.UpdateRelationPersonNamesUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.AaregEmploymentLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.CachedPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.EregNarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedEmployeeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedLeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedNarmestelederrelasjonSearchRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedRelationPersonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerDiscardedEmailAddressMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerNameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerNarmestelederRegisterMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerRelationPersonNameUpdateMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.PdlBulkPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.PdlPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.ValkeyPersonDetailsCache
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

private val LOOKUP_DISCARDED_EMAIL_ADDRESS_METRICS = named("lookupDiscardedEmailAddressMetrics")
private val EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_METRICS = named("employeeLinemanagerDiscardedEmailAddressMetrics")

fun narmestelederrelasjonModule() = module {
    single<PublishNarmestelederrelasjon> { KafkaPublishNarmestelederrelasjon(get()) }
    single<PublishNarmestelederrelasjonRevocation> { KafkaPublishNarmestelederrelasjonRevocation(get()) }
    single<NarmestelederrelasjonRepository> { ExposedNarmestelederrelasjonRepository(get()) }
    single<ActiveNarmestelederrelasjonRepository> { ExposedActiveNarmestelederrelasjonRepository(get<ExposedDatabase>()) }
    single<DiscardedEmailAddressMetrics>(LOOKUP_DISCARDED_EMAIL_ADDRESS_METRICS) { MicrometerDiscardedEmailAddressMetrics.lookupNarmesteleder() }
    single { LookupActiveNarmestelederUseCase(get(), get(LOOKUP_DISCARDED_EMAIL_ADDRESS_METRICS)) }
    single<NarmestelederrelasjonSearchRepository> { ExposedNarmestelederrelasjonSearchRepository(get<ExposedDatabase>()) }
    single { SearchActiveNarmestelederrelasjonerUseCase(get(), get()) }
    single<EmployeeNarmestelederrelasjonRepository> { ExposedEmployeeNarmestelederrelasjonRepository(get<ExposedDatabase>()) }
    single<DiscardedEmailAddressMetrics>(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_METRICS) {
        MicrometerDiscardedEmailAddressMetrics.employeeLinemanager()
    }
    single { ListActiveNarmesteledereForEmployeeUseCase(get(), get(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_METRICS)) }
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
    single<LeesahNarmestelederrelasjonRepository> { ExposedLeesahNarmestelederrelasjonRepository(get<ExposedDatabase>()) }
    single<NarmestelederRegisterMetrics> { MicrometerNarmestelederRegisterMetrics() }
    single { PersistNarmestelederrelasjonerFromLeesahUseCase(get(), get()) }
    single<RelationPersonRepository> { ExposedRelationPersonRepository(get<ExposedDatabase>()) }
    single<BulkPersonLookup> { PdlBulkPersonLookup(get()) }
    single<RelationPersonNameUpdateMetrics> { MicrometerRelationPersonNameUpdateMetrics() }
    single { EnrichPendingRelationPersonsUseCase(get(), get()) }
    single { UpdateRelationPersonNamesUseCase(get(), get(), get()) }
}
