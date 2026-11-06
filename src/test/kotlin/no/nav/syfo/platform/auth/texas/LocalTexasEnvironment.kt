package no.nav.syfo.platform.auth.texas

fun TexasEnvironment.Companion.createForLocal() = TexasEnvironment(
    tokenIntrospectionEndpoint = "http://localhost:3000/api/v1/introspect",
    tokenExchangeEndpoint = "http://localhost:3000/api/v1/token/exchange",
    tokenEndpoint = "http://localhost:3000/api/v1/token",
    exchangeTargetIsAltinnTilganger = "dev:teamsykefravr:istilgangskontroll",
    azurePreAuthorizedApps = setOf("syfo-budstikka"),
)
