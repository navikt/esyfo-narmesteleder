package no.nav.syfo.plugins

import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.application.valkey.ValkeyCache
import org.koin.dsl.module

internal fun cacheModule() = module {
    single { ValkeyCache(valkeyEnvironment = env().valkeyEnvironment) }
    single { PdlCache(valkeyCache = get()) }
    single { EregCache(valkeyCache = get()) }
}
