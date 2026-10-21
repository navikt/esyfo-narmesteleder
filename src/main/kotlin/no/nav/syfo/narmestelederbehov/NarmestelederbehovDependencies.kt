package no.nav.syfo.narmestelederbehov

import no.nav.syfo.application.environment.Environment
import no.nav.syfo.narmestelederbehov.api.narmestelederbehovApiUrl
import no.nav.syfo.narmestelederbehov.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.application.CompleteFulfilledNarmestelederbehovDialogsUseCase
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookup
import no.nav.syfo.narmestelederbehov.application.EmployerMainOrganizationLookup
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovDialogsUseCase
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahUseCase
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.LeesahFulfillmentMetrics
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationMetrics
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogExpiry
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogLifecycleRepository
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpiryRepository
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.SendPendingNarmestelederbehovDialogsUseCase
import no.nav.syfo.narmestelederbehov.infrastructure.AaregEmployerMainOrganizationLookup
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialogCreation
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialogExpiry
import no.nav.syfo.narmestelederbehov.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovDialogLifecycleRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovExpiryRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedOpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerLeesahFulfillmentMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerNarmestelederbehovCreationMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.NarmestelederbehovDialogFactory
import no.nav.syfo.narmestelederbehov.infrastructure.PdlEmployeeNameLookup
import no.nav.syfo.narmestelederbehov.infrastructure.PdlEmployeePersonLookup
import no.nav.syfo.narmestelederbehov.infrastructure.SendDialogTask
import no.nav.syfo.narmestelederbehov.infrastructure.UpdateDialogTask
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import kotlin.time.Duration

fun narmestelederbehovDependencies() = module {
    single { NarmestelederbehovCreationSettings(persistenceEnabled = get<Environment>().otherProperties.persistLeesahNlBehov) }
    single {
        NarmestelederbehovDialogFactory(
            narmestelederbehovApiUrl = narmestelederbehovApiUrl(get<Environment>().otherProperties.publicIngressUrl),
            frontendBaseUrl = get<Environment>().otherProperties.frontendBaseUrl,
        )
    }
    single<NarmestelederbehovRepository> { ExposedNarmestelederbehovRepository(get<Database>()) }
    single<NarmestelederbehovDialogLifecycleRepository> { ExposedNarmestelederbehovDialogLifecycleRepository(get<Database>()) }
    single<NarmestelederbehovDialogExpiry> { DialogportenNarmestelederbehovDialogExpiry(get()) }
    single {
        CompleteFulfilledNarmestelederbehovDialogsUseCase(
            lifecycleRepository = get(),
            repository = get(),
            dialog = get(),
            clock = get(),
        )
    }
    single { ExpireNarmestelederbehovDialogsUseCase(repository = get(), dialog = get(), clock = get()) }
    single {
        UpdateDialogTask(
            completeFulfilledDialogs = get(),
            expireDialogs = get(),
            pollingInterval = Duration.parse(get<Environment>().otherProperties.updateDialogportenTaskProperties.pollingDelay),
        )
    }
    single {
        SendPendingNarmestelederbehovDialogsUseCase(
            repository = get(),
            personLookup = get(),
            dialog = get(),
            clock = get(),
        )
    }
    single { SendDialogTask(sendPendingDialogs = get()) }
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
    single<ActiveSykmeldingLookup> { DinesykmeldteActiveSykmeldingLookup(get()) }
    single<EmployerMainOrganizationLookup> { AaregEmployerMainOrganizationLookup(get()) }
    single<NarmestelederbehovDialogCreation> { DialogportenNarmestelederbehovDialogCreation(client = get(), dialogFactory = get()) }
    single<EmployeePersonLookup> { PdlEmployeePersonLookup(get()) }
    single<NarmestelederbehovCreationMetrics> { MicrometerNarmestelederbehovCreationMetrics() }
    single<CreateNarmestelederbehov> {
        CreateNarmestelederbehovUseCase(
            settings = get(),
            repository = get(),
            activeSykmelding = get(),
            mainOrganization = get(),
            personLookup = get(),
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
