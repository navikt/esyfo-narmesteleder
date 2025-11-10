package no.nav.syfo.application

data class OtherEnvironmentProperties(
    val electorPath: String,
    val frontendBaseUrl: String,
    val publicIngressUrl: String,
    val persistLeesahNlBehov: Boolean,
) {
    companion object {
        fun createFromEnvVars() =
            OtherEnvironmentProperties(
                electorPath = getEnvVar("ELECTOR_PATH"),
                frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
                publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
                persistLeesahNlBehov = true
            )
        fun createForLocal() =
            OtherEnvironmentProperties(
                electorPath = "esyfo-narmesteleder",
                frontendBaseUrl = "http://localhost:3000",
                publicIngressUrl = "http://localhost:8080",
                persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "false").toBoolean(),
            )
    }
}
