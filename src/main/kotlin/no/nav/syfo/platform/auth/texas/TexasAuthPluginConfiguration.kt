package no.nav.syfo.platform.auth.texas

import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

class TexasAuthPluginConfiguration(
    var client: TexasHttpClient? = null,
)

internal fun TexasHttpClient?.requireConfigured(pluginName: String): TexasHttpClient = this ?: error("$pluginName installed without TexasHttpClient")
