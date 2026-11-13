package no.nav.syfo.narmestelederbehov

import no.nav.syfo.narmestelederbehov.api.narmestelederbehovApiUrl
import no.nav.syfo.narmestelederbehov.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.DialogDispatchMetrics
import no.nav.syfo.narmestelederbehov.application.DialogDispatchQueue
import no.nav.syfo.narmestelederbehov.application.DialogDispatchSettings
import no.nav.syfo.narmestelederbehov.application.DialogDispatchStatsReader
import no.nav.syfo.narmestelederbehov.application.DialogDispatchWork
import no.nav.syfo.narmestelederbehov.application.DialogExpiryInspection
import no.nav.syfo.narmestelederbehov.application.DispatchNarmestelederbehovDialogUseCase
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookup
import no.nav.syfo.narmestelederbehov.application.EmployerMainOrganizationLookup
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahRepository
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahUseCase
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.LeesahFulfillmentMetrics
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationMetrics
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogExpiry
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeNameRepository
import no.nav.syfo.narmestelederbehov.infrastructure.AaregEmployerMainOrganizationLookup
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialogExpiry
import no.nav.syfo.narmestelederbehov.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedDialogDispatchRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovFulfillmentRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovLifecycleRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovReadRepository
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerDialogDispatchMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerLeesahFulfillmentMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerNarmestelederbehovCreationMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.NarmestelederbehovDialogFactory
import no.nav.syfo.narmestelederbehov.infrastructure.PdlEmployeeNameLookup
import no.nav.syfo.narmestelederbehov.infrastructure.PdlEmployeePersonLookup
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.binds
import org.koin.dsl.module

fun narmestelederbehovDependencies(settings: NarmestelederbehovSettings) = module {
    single {
        NarmestelederbehovDialogFactory(
            narmestelederbehovApiUrl = narmestelederbehovApiUrl(settings.dialogporten.publicIngressUrl),
            frontendBaseUrl = settings.dialogporten.frontendBaseUrl,
        )
    }
    single { ExposedNarmestelederbehovReadRepository(get<Database>()) } binds arrayOf(
        GetNarmestelederbehovRepository::class,
        NarmestelederbehovEmployeeNameRepository::class,
        ListNarmestelederbehovRepository::class,
    )
    single { ExposedNarmestelederbehovFulfillmentRepository(get<Database>()) } binds arrayOf(
        FulfillNarmestelederbehovRepository::class,
        FulfillNarmestelederbehovFromLeesahRepository::class,
    )
    single { ExposedNarmestelederbehovLifecycleRepository(get<Database>()) } binds arrayOf(
        CreateNarmestelederbehovRepository::class,
        ExpireNarmestelederbehovRepository::class,
    )
    single { ExposedDialogDispatchRepository(get()) } binds arrayOf(
        DialogDispatchQueue::class,
        DialogDispatchWork::class,
        DialogDispatchStatsReader::class,
    )
    single { DialogDispatchSettings() }
    single { MicrometerDialogDispatchMetrics(get()) } binds arrayOf(DialogDispatchMetrics::class)
    single {
        DispatchNarmestelederbehovDialogUseCase(
            dispatchQueue = get(),
            dispatchWork = get(),
            dialogCreation = get(),
            dialogCompletion = get(),
            dialogExpiry = get(),
            dialogExpiryInspection = get(),
            employeePersonLookup = get(),
            clock = get(),
            dispatchMetrics = get(),
            settings = get(),
        )
    }
    single { DialogportenNarmestelederbehovDialogExpiry(get()) } binds arrayOf(
        NarmestelederbehovDialogExpiry::class,
        DialogExpiryInspection::class,
    )
    single<NarmestelederbehovDialog> { DialogportenNarmestelederbehovDialog(get()) }
    single { FulfillNarmestelederbehovUseCase(get(), get(), get()) }
    single<LeesahFulfillmentMetrics> { MicrometerLeesahFulfillmentMetrics() }
    single {
        FulfillNarmestelederbehovFromLeesahUseCase(
            behovRepository = get(),
            metrics = get(),
        )
    }
    single<ActiveSykmeldingLookup> { DinesykmeldteActiveSykmeldingLookup(get()) }
    single<EmployerMainOrganizationLookup> { AaregEmployerMainOrganizationLookup(get()) }
    single<NarmestelederbehovDialogCreation> { DialogportenNarmestelederbehovDialogCreation(client = get(), dialogFactory = get()) }
    single<EmployeePersonLookup> { PdlEmployeePersonLookup(get()) }
    single<NarmestelederbehovCreationMetrics> { MicrometerNarmestelederbehovCreationMetrics() }
    single<CreateNarmestelederbehov> {
        CreateNarmestelederbehovUseCase(
            repository = get(),
            activeSykmelding = get(),
            mainOrganization = get(),
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
