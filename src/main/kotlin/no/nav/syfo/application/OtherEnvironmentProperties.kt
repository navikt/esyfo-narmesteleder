package no.nav.syfo.application

data class OtherEnvironmentProperties(
    val electorPath: String,
    val frontendBaseUrl: String,
    val publicIngressUrl: String,
) {
    companion object {
        fun createFromEnvVars() =
            OtherEnvironmentProperties(
                electorPath = getEnvVar("ELECTOR_PATH"),
                frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
                publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
            )
        fun createForLocal() =
            OtherEnvironmentProperties(
                electorPath = "esyfo-narmesteleder",
                frontendBaseUrl = "http://localhost:3000",
                publicIngressUrl = "http://localhost:8080",
            )
    }
}
