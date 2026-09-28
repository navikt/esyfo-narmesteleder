package no.nav.syfo.texas

import no.nav.syfo.texas.client.TexasHttpClient

class TexasAuthPluginConfiguration(
    var client: TexasHttpClient? = null,
)

internal fun TexasHttpClient?.requireConfigured(pluginName: String): TexasHttpClient = this ?: error("$pluginName installed without TexasHttpClient")
