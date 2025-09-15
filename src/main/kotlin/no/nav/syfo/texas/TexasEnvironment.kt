package no.nav.syfo.texas

data class TexasEnvironment(
    val tokenIntrospectionEndpoint: String,
    val tokenEndpoint: String,
    val tokenExchangeEndpoint: String,
    val exchangeTargetDineSykmeldte: String,
    val exchangeTargetIsDialogmelding: String,
    val exchangeTargetIsTilgangskontroll: String,
)
