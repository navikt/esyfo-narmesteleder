package no.nav.syfo.application

data class OtherEnvironmentProperties(
    val electorPath: String,
    val frontendBaseUrl: String,
    val publicIngressUrl: String,
    val updateDialogportenTaskProperties: UpdateDialogportenTaskProperties
    val persistLeesahNlBehov: Boolean,
) {
    companion object {
        fun createFromEnvVars() =
            OtherEnvironmentProperties(
                electorPath = getEnvVar("ELECTOR_PATH"),
                frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
                publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
                persistLeesahNlBehov = true
                updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createFromEnvVars()
            )

        fun createForLocal() =
            OtherEnvironmentProperties(
                electorPath = "esyfo-narmesteleder",
                frontendBaseUrl = "http://localhost:3000",
                publicIngressUrl = "http://localhost:8080",
                updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
                persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "false").toBoolean(),
            )
    }
}

data class UpdateDialogportenTaskProperties(
    val pollingDelay: String,
) {
    companion object {
        fun createFromEnvVars() =
            UpdateDialogportenTaskProperties(
                pollingDelay = getEnvVar("DIALOGPORTEN_UPDATE_POLLING_DELAY", "5m"),
            )

        fun createForLocal() =
            UpdateDialogportenTaskProperties(
                pollingDelay = "30s",
                persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "false").toBoolean(),
            )
    }
}
