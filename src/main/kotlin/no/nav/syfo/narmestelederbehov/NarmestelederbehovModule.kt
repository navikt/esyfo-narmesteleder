package no.nav.syfo.narmestelederbehov

import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.PdlPersonLookup
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

fun narmestelederbehovModule() = module {
    single<NarmestelederbehovRepository> { ExposedNarmestelederbehovRepository(get<Database>()) }
    single<NarmestelederbehovDialog> { DialogportenNarmestelederbehovDialog(get()) }
    single { FulfillNarmestelederbehovUseCase(get(), get(), get(), get()) }
    single {
        GetNarmestelederbehovUseCase(
            repository = get(),
            organizationAccess = get(),
            personLookup = PdlPersonLookup(get()),
        )
    }
}
