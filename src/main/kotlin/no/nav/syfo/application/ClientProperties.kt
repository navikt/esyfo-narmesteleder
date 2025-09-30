package no.nav.syfo.application

import kotlin.String

data class ClientProperties(
    val pdlBaseUrl: String,
    val pdlScope: String,
    val aaregBaseUrl: String,
    val aaregScope: String,
) {
    companion object {
        fun createForLocal() = ClientProperties(
            pdlBaseUrl = "https://pdl-api.dev.intern.nav.no",
            pdlScope = "pdl",
            aaregBaseUrl = "",
            aaregScope = "aareg",
        )

        fun createFromEnvVars() =
            ClientProperties(
                pdlBaseUrl = getEnvVar("PDL_BASE_URL"),
                pdlScope = getEnvVar("PDL_SCOPE"),
                aaregBaseUrl = getEnvVar("AAREG_BASE_URL"),
                aaregScope = getEnvVar("AAREG_SCOPE"),
            )
    }
}
