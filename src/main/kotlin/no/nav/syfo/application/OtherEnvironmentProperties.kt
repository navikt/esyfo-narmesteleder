package no.nav.syfo.application

data class OtherEnvironmentProperties(
    val electorPath: String,
    val frontendUrl: String,
    val publicIngressUrl: String,
) {
    companion object {
        fun createFromEnvVars() =
            OtherEnvironmentProperties(
                electorPath = getEnvVar("ELECTOR_PATH"),
                frontendUrl = getEnvVar("FRONTEND_URL"),
                publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
            )
        fun createForLocal() =
            OtherEnvironmentProperties(
                electorPath = "esyfo-narmesteleder",
                frontendUrl = "http://localhost:3000",
                publicIngressUrl = "http://localhost:8080",
            )
    }
}
