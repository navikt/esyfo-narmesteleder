package no.nav.syfo.narmestelederbehov

import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpiryRepository
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.PdlEmployeeNameLookup
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.binds
import org.koin.dsl.module

fun narmestelederbehovModule() = module {
    single { ExposedNarmestelederbehovRepository(get<Database>()) } binds arrayOf(
        NarmestelederbehovRepository::class,
        OpenNarmestelederbehovRepository::class,
        NarmestelederbehovExpiryRepository::class,
    )
    single<NarmestelederbehovDialog> { DialogportenNarmestelederbehovDialog(get()) }
    single { FulfillNarmestelederbehovUseCase(get(), get(), get(), get()) }
    single<EmployeeNameLookup> { PdlEmployeeNameLookup(get()) }
    single { NarmestelederbehovEmployeeName(repository = get(), employeeNameLookup = get()) }
    single { ListNarmestelederbehovUseCase(repository = get(), organizationAccess = get(), employeeName = get()) }
    single { ExpireNarmestelederbehovUseCase(repository = get(), settings = get(), clock = get()) }
    single {
        GetNarmestelederbehovUseCase(
            repository = get(),
            organizationAccess = get(),
            employeeName = get(),
        )
    }
}
