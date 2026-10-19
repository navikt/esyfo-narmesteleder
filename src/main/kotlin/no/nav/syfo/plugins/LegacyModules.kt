package no.nav.syfo.plugins

import kotlinx.coroutines.Dispatchers
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.pdl.PdlService
import org.koin.dsl.module

// Registrations for code that has not yet moved into a capability module (ADR-0002).
internal fun legacyRepositoriesModule() = module {
    single<NarmestelederDb> { PostgresNarmestelederDb(database = get(), dispatcher = Dispatchers.IO) }
}

internal fun legacyServicesModule() = module {
    single { PdlService(pdlClient = get()) }
    single {
        DialogportenService(
            dialogportenClient = get(),
            narmestelederDb = get(),
            dialogFactory = get(),
            pdlService = get(),
        )
    }

    single { NlBehovLeesahHandler(createNarmestelederbehov = get()) }
}
