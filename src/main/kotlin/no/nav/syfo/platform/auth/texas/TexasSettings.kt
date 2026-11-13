package no.nav.syfo.platform.auth.texas

data class TexasSettings(
    val tokenIntrospectionEndpoint: String,
    val tokenEndpoint: String,
    val tokenExchangeEndpoint: String,
    val exchangeTargetIsAltinnTilganger: String,
)

fun altinnTilgangerExchangeTarget(clusterName: String) = "$clusterName:fager:arbeidsgiver-altinn-tilganger"
