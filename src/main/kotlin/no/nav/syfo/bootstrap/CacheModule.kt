package no.nav.syfo.bootstrap

import no.nav.syfo.integration.ereg.EregCache
import no.nav.syfo.platform.cache.ValkeyCache
import no.nav.syfo.platform.cache.ValkeySettings
import org.koin.dsl.module

internal fun cacheModule(valkey: ValkeySettings) = module {
    single { ValkeyCache(valkeyEnvironment = valkey) }
    single { EregCache(valkeyCache = get()) }
}
