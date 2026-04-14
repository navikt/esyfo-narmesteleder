package no.nav.syfo.application.environment

data class OtherEnvironmentProperties(
    val electorPath: String,
    val electorSSEUrl: String,
    val frontendBaseUrl: String,
    val publicIngressUrl: String,
    val updateDialogportenTaskProperties: UpdateDialogportenTaskProperties,
    val maintenanceTaskDelay: String,
    val persistLeesahNlBehov: Boolean,
    val isDialogportenBackgroundTaskEnabled: Boolean,
    val dialogportenIsApiOnly: Boolean,
    val daysAfterTomToExpireBehovs: Long,
    val persistSendtSykmelding: Boolean,
    val maintenanceTaskEnabled: Boolean,
    val persistNarmestelederRegister: Boolean,
) {
    companion object {
        fun createFromEnvVars() = OtherEnvironmentProperties(
            electorPath = getEnvVar("ELECTOR_PATH"),
            electorSSEUrl = getEnvVar("ELECTOR_SSE_URL"),
            frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
            publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
            persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "true").toBoolean(),
            isDialogportenBackgroundTaskEnabled = getEnvVar("DIALOGPORTEN_TASK_ENABLED").toBoolean(),
            dialogportenIsApiOnly = getEnvVar("DIALOGPORTEN_API_ONLY").toBoolean(),
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createFromEnvVars(),
            persistSendtSykmelding = getEnvVar("PERSIST_SENDT_SYKMELDING", "false").toBoolean(),
            daysAfterTomToExpireBehovs = getEnvVar(
                "DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS",
                "16"
            ).toLong(),
            maintenanceTaskDelay = getEnvVar("MAINTENANCE_TASK_DELAY", "24h"),
            maintenanceTaskEnabled = getEnvVar("BEHOV_MAINTENANCE_TASK_ENABLED", "false").toBoolean(),
            persistNarmestelederRegister = getEnvVar("PERSIST_NARMESTELEDER_REGISTER", "false").toBoolean(),
        )

        fun createForLocal() = OtherEnvironmentProperties(
            electorPath = "esyfo-narmesteleder",
            frontendBaseUrl = "http://localhost:3000",
            publicIngressUrl = "http://localhost:8080",
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
            persistLeesahNlBehov = true,
            isDialogportenBackgroundTaskEnabled = true,
            dialogportenIsApiOnly = false,
            persistSendtSykmelding = true,
            daysAfterTomToExpireBehovs = 0,
            maintenanceTaskDelay = "1m",
            maintenanceTaskEnabled = true,
            electorSSEUrl = "not.applicable",
            persistNarmestelederRegister = true,
        )
    }
}

data class UpdateDialogportenTaskProperties(
    val pollingDelay: String,
) {
    companion object {
        fun createFromEnvVars() = UpdateDialogportenTaskProperties(
            pollingDelay = getEnvVar("DIALOGPORTEN_UPDATE_POLLING_DELAY", "5m"),
        )

        fun createForLocal() = UpdateDialogportenTaskProperties(
            pollingDelay = "30s",
        )
    }
}
