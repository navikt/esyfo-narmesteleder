package no.nav.syfo.application

data class ClientProperties(
    val pdlBaseUrl: String,
    val pdlScope: String,
    val altinnTilgangerBaseUrl: String,
    val aaregBaseUrl: String,
    val dinesykmeldteBaseUrl: String,
    val aaregScope: String,
    val dinesykmeldteScope: String,
    val persistLeesahNlBehov: Boolean,
) {
    companion object {
        fun createForLocal() = ClientProperties(
            pdlBaseUrl = "https://pdl-api.dev.intern.nav.no",
            pdlScope = "pdl",
            altinnTilgangerBaseUrl = "https://altinn-tilganger-api.dev.intern.nav.no",
            aaregBaseUrl = "",
            dinesykmeldteBaseUrl = "",
            aaregScope = "aareg",
            persistLeesahNlBehov = true,
            dinesykmeldteScope = "",
        )

        fun createFromEnvVars() =
            ClientProperties(
                pdlBaseUrl = getEnvVar("PDL_BASE_URL"),
                pdlScope = getEnvVar("PDL_SCOPE"),
                aaregBaseUrl = getEnvVar("AAREG_BASE_URL"),
                aaregScope = getEnvVar("AAREG_SCOPE"),
                dinesykmeldteBaseUrl = getEnvVar("DINESYMELDTE_BASEURL"),
                altinnTilgangerBaseUrl = getEnvVar("ALTINN_TILGANGER_BASE_URL"),
                persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "false").toBoolean(),
                dinesykmeldteScope = getEnvVar("DINESYMELDTE_BASEURL")
            )
    }
}
