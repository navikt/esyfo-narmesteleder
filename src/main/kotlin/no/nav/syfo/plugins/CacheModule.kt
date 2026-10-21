package no.nav.syfo.plugins

import no.nav.syfo.integration.ereg.EregCache
import no.nav.syfo.platform.cache.ValkeyCache
import org.koin.dsl.module

internal fun cacheModule() = module {
    single { ValkeyCache(valkeyEnvironment = env().valkeyEnvironment) }
    single { EregCache(valkeyCache = get()) }
}
