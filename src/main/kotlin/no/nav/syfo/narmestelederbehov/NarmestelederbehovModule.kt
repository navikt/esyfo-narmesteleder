package no.nav.syfo.narmestelederbehov

import no.nav.syfo.narmestelederbehov.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.application.EmploymentLookup
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.ManagerNameValidationMetrics
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.PersonLookup
import no.nav.syfo.narmestelederbehov.infrastructure.AaregEmploymentLookup
import no.nav.syfo.narmestelederbehov.infrastructure.DbNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.infrastructure.LegacyManagerNameValidationMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.PdlPersonLookup
import org.koin.dsl.module

fun narmestelederbehovModule() = module {
    single<NarmestelederbehovRepository> { DbNarmestelederbehovRepository(get()) }
    single<ActiveSykmeldingLookup> { DinesykmeldteActiveSykmeldingLookup(get()) }
    single<EmploymentLookup> { AaregEmploymentLookup(get()) }
    single<PersonLookup> { PdlPersonLookup(get()) }
    single<ManagerNameValidationMetrics> { LegacyManagerNameValidationMetrics() }
    single<NarmestelederbehovDialog> { DialogportenNarmestelederbehovDialog(get(), get()) }
    single { FulfillNarmestelederbehovUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
}
