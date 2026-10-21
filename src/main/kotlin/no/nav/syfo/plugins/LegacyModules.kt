package no.nav.syfo.plugins

import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import org.koin.dsl.module

// Registrations for code that has not yet moved into a capability module (ADR-0002).
internal fun legacyServicesModule() = module {
    single { NlBehovLeesahHandler(createNarmestelederbehov = get()) }
}
