package no.nav.syfo.application

import kotlin.String

data class ClientProperties(
    val pdlBaseUrl: String,
    val pdlScope: String,
    val altinnTilgangerBaseUrl: String,
) {
    companion object {
        fun createForLocal() = ClientProperties(
            pdlBaseUrl = "https://pdl-api.dev.intern.nav.no",
            pdlScope = "pdl",
            altinnTilgangerBaseUrl = "https://altinn-tilganger-api.dev.intern.nav.no",
        )

        fun createFromEnvVars() =
            ClientProperties(
                pdlBaseUrl = getEnvVar("PDL_BASE_URL"),
                pdlScope = getEnvVar("PDL_SCOPE"),
                altinnTilgangerBaseUrl = getEnvVar("ALTINN_TILGANGER_BASE_URL"),
            )
    }
}
