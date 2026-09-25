package no.nav.syfo.narmestelederbehov

import no.nav.syfo.narmestelederbehov.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.application.EmploymentLookup
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.ManagerNameValidationMetrics
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.PersonLookup
import no.nav.syfo.narmestelederbehov.infrastructure.AaregEmploymentLookup
import no.nav.syfo.narmestelederbehov.infrastructure.CachedPersonLookup
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.LegacyManagerNameValidationMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.PdlPersonLookup
import no.nav.syfo.narmestelederbehov.infrastructure.ValkeyPersonDetailsCache
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

fun narmestelederbehovModule() = module {
    single<NarmestelederbehovRepository> { ExposedNarmestelederbehovRepository(get<Database>()) }
    single<ActiveSykmeldingLookup> { DinesykmeldteActiveSykmeldingLookup(get()) }
    single<EmploymentLookup> { AaregEmploymentLookup(get()) }
    single<PersonLookup> { CachedPersonLookup(PdlPersonLookup(get()), ValkeyPersonDetailsCache(get())) }
    single<ManagerNameValidationMetrics> { LegacyManagerNameValidationMetrics() }
    single<NarmestelederbehovDialog> { DialogportenNarmestelederbehovDialog(get()) }
    single { FulfillNarmestelederbehovUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
}
