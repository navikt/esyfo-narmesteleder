package no.nav.syfo.application.environment

data class ClientProperties(
    val pdlBaseUrl: String,
    val pdlScope: String,
    val altinnTilgangerBaseUrl: String,
    val aaregBaseUrl: String,
    val dinesykmeldteBaseUrl: String,
    val aaregScope: String,
    val dinesykmeldteScope: String,
    val altinn3BaseUrl: String,
    val pdpSubscriptionKey: String,
    val eregBaseUrl: String,
) {
    companion object {
        fun createForLocal() = ClientProperties(
            pdlBaseUrl = "https://pdl-api.dev.intern.nav.no",
            aaregBaseUrl = "",
            aaregScope = "aareg",
            altinnTilgangerBaseUrl = "https://altinn-tilganger-api.dev.intern.nav.no",
            dinesykmeldteBaseUrl = "",
            dinesykmeldteScope = "",
            pdlScope = "pdl",
            altinn3BaseUrl = "http://localhost:8080/dialogporten",
            pdpSubscriptionKey = "secret-key",
            eregBaseUrl = "https://ereg-test.intern.nav.no",
        )

        fun createFromEnvVars() = ClientProperties(
            aaregBaseUrl = getEnvVar("AAREG_BASE_URL"),
            aaregScope = getEnvVar("AAREG_SCOPE"),
            altinnTilgangerBaseUrl = getEnvVar("ALTINN_TILGANGER_BASE_URL"),
            dinesykmeldteBaseUrl = getEnvVar("DINESYMELDTE_BASEURL"),
            dinesykmeldteScope = getEnvVar("DINESYMELDTE_SCOPE"),
            pdlBaseUrl = getEnvVar("PDL_BASE_URL"),
            pdlScope = getEnvVar("PDL_SCOPE"),
            altinn3BaseUrl = getEnvVar("ALTINN_3_BASE_URL"),
            pdpSubscriptionKey = getEnvVar("PDP_SUBSCRIPTION_KEY"),
            eregBaseUrl = getEnvVar("EREG_BASE_URL"),
        )
    }
}
