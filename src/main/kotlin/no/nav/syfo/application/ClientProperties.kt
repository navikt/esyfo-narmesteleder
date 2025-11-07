package no.nav.syfo.application

data class ClientProperties(
    val pdlBaseUrl: String,
    val pdlScope: String,
    val altinnTilgangerBaseUrl: String,
    val aaregBaseUrl: String,
    val dinesykmeldteBaseUrl: String,
    val aaregScope: String,
    val dinesykmeldteScope: String,
    val dialogportenBasePath: String
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
            dialogportenBasePath = "http://localhost:8080/dialogporten"
        )

        fun createFromEnvVars() =
            ClientProperties(
                aaregBaseUrl = getEnvVar("AAREG_BASE_URL"),
                aaregScope = getEnvVar("AAREG_SCOPE"),
                altinnTilgangerBaseUrl = getEnvVar("ALTINN_TILGANGER_BASE_URL"),
                dinesykmeldteBaseUrl = getEnvVar("DINESYMELDTE_BASEURL"),
                dinesykmeldteScope = getEnvVar("DINESYMELDTE_SCOPE"),
                pdlBaseUrl = getEnvVar("PDL_BASE_URL"),
                pdlScope = getEnvVar("PDL_SCOPE"),
                dialogportenBasePath = getEnvVar("DIALOGPORTEN_BASE_URL")
            )
    }
}
