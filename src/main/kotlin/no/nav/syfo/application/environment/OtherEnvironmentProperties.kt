package no.nav.syfo.application.environment

data class OtherEnvironmentProperties(
    val electorPath: String,
    val frontendBaseUrl: String,
    val publicIngressUrl: String,
    val updateDialogportenTaskProperties: UpdateDialogportenTaskProperties,
    val persistLeesahNlBehov: Boolean,
    val isDialogporteBackgroundTaskEnabled: Boolean,
) {
    companion object {
        fun createFromEnvVars() =
            OtherEnvironmentProperties(
                electorPath = getEnvVar("ELECTOR_PATH"),
                frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
                publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
                persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "true").toBoolean(),
                isDialogporteBackgroundTaskEnabled = getEnvVar("DIALOGPORTEN_TASK_ENABLED").toBoolean(),
                updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createFromEnvVars()
            )

        fun createForLocal() =
            OtherEnvironmentProperties(
                electorPath = "esyfo-narmesteleder",
                frontendBaseUrl = "http://localhost:3000",
                publicIngressUrl = "http://localhost:8080",
                updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
                persistLeesahNlBehov = true,
                isDialogporteBackgroundTaskEnabled = true,
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
            )
    }
}
