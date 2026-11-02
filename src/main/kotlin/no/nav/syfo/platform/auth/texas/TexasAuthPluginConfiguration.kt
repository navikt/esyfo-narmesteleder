package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.Application
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import org.koin.ktor.ext.getKoin

class TexasAuthPluginConfiguration(
    var client: TexasHttpClient? = null,
)

/**
 * Uses the explicitly configured client, otherwise resolves it from Koin. Fails fast when neither is available.
 */
internal fun Application.resolveTexasHttpClient(configured: TexasHttpClient?, pluginName: String): TexasHttpClient = configured
    ?: runCatching { getKoin().getOrNull<TexasHttpClient>() }.getOrNull()
    ?: error("$pluginName installed without TexasHttpClient")
