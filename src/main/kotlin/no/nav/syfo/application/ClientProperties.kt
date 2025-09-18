package no.nav.syfo.application

import kotlin.String

data class ClientProperties(
    val pdlBaseUrl: String,
    val pdlScope: String,
) {
    companion object {
        fun createForLocal() = ClientProperties(
            pdlBaseUrl = "https://pdl-api.dev.intern.nav.no",
            pdlScope = "pdl",
        )

        fun createFromEnvVars() =
            ClientProperties(
                pdlBaseUrl = getEnvVar("PDL_BASE_URL"),
                pdlScope = getEnvVar("PDL_SCOPE"),
            )
    }
}
