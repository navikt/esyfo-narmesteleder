package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.Application
import io.ktor.server.application.pluginOrNull
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import org.koin.core.Koin
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin as KoinPlugin

class TexasAuthPluginConfiguration(
    var client: TexasHttpClient? = null,
)

/**
 * Uses the explicitly configured client, otherwise resolves it from Koin. Fails fast when neither is available.
 */
internal fun Application.resolveTexasHttpClient(configured: TexasHttpClient?, pluginName: String): TexasHttpClient = configured
    ?: koinOrNull()?.getOrNull<TexasHttpClient>()
    ?: error("$pluginName installed without TexasHttpClient")

private fun Application.koinOrNull(): Koin? = pluginOrNull(KoinPlugin)?.let { getKoin() }
