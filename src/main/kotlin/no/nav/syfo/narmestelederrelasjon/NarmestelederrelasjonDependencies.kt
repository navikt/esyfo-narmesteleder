package no.nav.syfo.narmestelederrelasjon

import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.DiscardedEmailAddressMetrics
import no.nav.syfo.narmestelederrelasjon.application.HasActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.NameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.employee.EmployeeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.employee.ListActiveNarmesteledereForEmployeeUseCase
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.CheckNarmestelederrelasjonEmploymentUseCase
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentCheckMetrics
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentCheckSettings
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentHistoryLookup
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentReconciliationRepository
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.RecordSourceEmploymentRevocationUseCase
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.SeedNarmestelederrelasjonEmploymentChecksUseCase
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentLookup
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.establish.PublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.get.GetNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.get.NarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.leesah.LeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.leesah.NarmestelederRegisterMetrics
import no.nav.syfo.narmestelederrelasjon.application.leesah.PersistNarmestelederrelasjonerFromLeesahUseCase
import no.nav.syfo.narmestelederrelasjon.application.lookup.HasActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.lookup.LookupActiveNarmestelederUseCase
import no.nav.syfo.narmestelederrelasjon.application.relationperson.BulkPersonLookup
import no.nav.syfo.narmestelederrelasjon.application.relationperson.EnrichPendingRelationPersonsUseCase
import no.nav.syfo.narmestelederrelasjon.application.relationperson.RelationPersonNameUpdateMetrics
import no.nav.syfo.narmestelederrelasjon.application.relationperson.RelationPersonRepository
import no.nav.syfo.narmestelederrelasjon.application.relationperson.UpdateRelationPersonNamesUseCase
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonFromSendtSykmelding
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonFromSendtSykmeldingUseCase
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.search.NarmestelederrelasjonSearchRepository
import no.nav.syfo.narmestelederrelasjon.application.search.SearchActiveNarmestelederrelasjonerUseCase
import no.nav.syfo.narmestelederrelasjon.application.submit.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.AaregEmploymentHistoryLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.AaregEmploymentLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.CachedPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.EregNarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedEmployeeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedEmploymentReconciliationRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedLeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedNarmestelederrelasjonSearchRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.ExposedRelationPersonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerDiscardedEmailAddressMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerEmploymentCheckMetrics
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

fun narmestelederrelasjonDependencies(settings: NarmestelederrelasjonSettings) = module {
    single<PublishNarmestelederrelasjon> { KafkaPublishNarmestelederrelasjon(get()) }
    single<PublishNarmestelederrelasjonRevocation> { KafkaPublishNarmestelederrelasjonRevocation(get()) }
    single<NarmestelederrelasjonRepository> { ExposedNarmestelederrelasjonRepository(get()) }
    single<ActiveNarmestelederrelasjonRepository> { ExposedActiveNarmestelederrelasjonRepository(get<ExposedDatabase>()) }
    single<HasActiveNarmestelederrelasjon> { HasActiveNarmestelederrelasjonUseCase(get()) }
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
    single {
        PersistNarmestelederrelasjonerFromLeesahUseCase(
            repository = get(),
            metrics = get(),
            recordSourceEmploymentRevocation = if (settings.employmentCheckObserveSourceEnabled) get() else null,
        )
    }
    single { EmploymentCheckSettings() }
    single<EmploymentHistoryLookup> { AaregEmploymentHistoryLookup(get()) }
    single<EmploymentReconciliationRepository> { ExposedEmploymentReconciliationRepository(get<ExposedDatabase>()) }
    single<EmploymentCheckMetrics> { MicrometerEmploymentCheckMetrics() }
    single {
        CheckNarmestelederrelasjonEmploymentUseCase(
            repository = get(),
            employmentHistoryLookup = get(),
            settings = get(),
            clock = get(),
            metrics = get(),
            publisher = get(),
            revokeEnabled = settings.employmentCheckRevokeEnabled,
        )
    }
    single { SeedNarmestelederrelasjonEmploymentChecksUseCase(get(), get(), get(), get()) }
    single { RecordSourceEmploymentRevocationUseCase(get(), get(), get()) }
    single<RelationPersonRepository> { ExposedRelationPersonRepository(get<ExposedDatabase>()) }
    single<BulkPersonLookup> { PdlBulkPersonLookup(get()) }
    single<RelationPersonNameUpdateMetrics> { MicrometerRelationPersonNameUpdateMetrics() }
    single { EnrichPendingRelationPersonsUseCase(get(), get()) }
    single { UpdateRelationPersonNamesUseCase(get(), get(), get()) }
}
