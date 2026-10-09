package no.nav.syfo.narmestelederbehov

import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.EmployerMainOrganizationLookup
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahUseCase
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.LeesahFulfillmentMetrics
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationMetrics
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpiryRepository
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.AaregEmployerMainOrganizationLookup
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.infrastructure.DinesykmeldteNarmestelederbehovActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovExpiryRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedOpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerLeesahFulfillmentMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerNarmestelederbehovCreationMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.PdlEmployeeNameLookup
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

fun narmestelederbehovModule() = module {
    single<NarmestelederbehovRepository> { ExposedNarmestelederbehovRepository(get<Database>()) }
    single<OpenNarmestelederbehovRepository> { ExposedOpenNarmestelederbehovRepository(get<Database>()) }
    single<NarmestelederbehovExpiryRepository> { ExposedNarmestelederbehovExpiryRepository(get<Database>()) }
    single<NarmestelederbehovDialog> { DialogportenNarmestelederbehovDialog(get()) }
    single { FulfillNarmestelederbehovUseCase(get(), get(), get(), get()) }
    single<LeesahFulfillmentMetrics> { MicrometerLeesahFulfillmentMetrics() }
    single {
        FulfillNarmestelederbehovFromLeesahUseCase(
            behovRepository = get(),
            metrics = get(),
            dialog = get(),
        )
    }
    single<NarmestelederbehovActiveSykmeldingLookup> { DinesykmeldteNarmestelederbehovActiveSykmeldingLookup(get()) }
    single<EmployerMainOrganizationLookup> { AaregEmployerMainOrganizationLookup(get()) }
    single<NarmestelederbehovDialogCreation> { DialogportenNarmestelederbehovDialogCreation(get()) }
    single<NarmestelederbehovCreationMetrics> { MicrometerNarmestelederbehovCreationMetrics() }
    single<CreateNarmestelederbehov> {
        CreateNarmestelederbehovUseCase(
            settings = get(),
            repository = get(),
            activeSykmelding = get(),
            mainOrganization = get(),
            dialog = get(),
            metrics = get(),
        )
    }
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
