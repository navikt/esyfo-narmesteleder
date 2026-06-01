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
    val daysAfterTomToExpireBehovs: Long,
    val persistSendtSykmelding: Boolean,
    val maintenanceTaskEnabled: Boolean,
    val persistNarmestelederRegister: Boolean,
    val pdlLeesahConsumerEnabled: Boolean,
    val personEnrichmentTaskDelay: String,
    val personEnrichmentTaskEnabled: Boolean,
) {
    companion object {
        fun createFromEnvVars() = OtherEnvironmentProperties(
            electorPath = getEnvVar("ELECTOR_PATH"),
            electorSSEUrl = getEnvVar("ELECTOR_SSE_URL"),
            frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
            publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
            persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "true").toBoolean(),
            isDialogportenBackgroundTaskEnabled = getEnvVar("DIALOGPORTEN_TASK_ENABLED").toBoolean(),
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createFromEnvVars(),
            persistSendtSykmelding = getEnvVar("PERSIST_SENDT_SYKMELDING", "false").toBoolean(),
            daysAfterTomToExpireBehovs = getEnvVar(
                "DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS",
                "16"
            ).toLong(),
            maintenanceTaskDelay = getEnvVar("MAINTENANCE_TASK_DELAY", "24h"),
            maintenanceTaskEnabled = getEnvVar("BEHOV_MAINTENANCE_TASK_ENABLED", "false").toBoolean(),
            persistNarmestelederRegister = getEnvVar("PERSIST_NARMESTELEDER_REGISTER", "false").toBoolean(),
            pdlLeesahConsumerEnabled = getEnvVar("PDL_LEESAH_CONSUMER_ENABLED", "false").toBoolean(),
            personEnrichmentTaskDelay = getEnvVar("PERSON_ENRICHMENT_TASK_DELAY", "5m"),
            personEnrichmentTaskEnabled = getEnvVar("PERSON_ENRICHMENT_TASK_ENABLED", "false").toBoolean(),
        )

        fun createForLocal() = OtherEnvironmentProperties(
            electorPath = "esyfo-narmesteleder",
            frontendBaseUrl = "http://localhost:3000",
            publicIngressUrl = "http://localhost:8080",
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
            persistLeesahNlBehov = true,
            isDialogportenBackgroundTaskEnabled = true,
            persistSendtSykmelding = true,
            daysAfterTomToExpireBehovs = 0,
            maintenanceTaskDelay = "1m",
            maintenanceTaskEnabled = true,
            electorSSEUrl = "not.applicable",
            persistNarmestelederRegister = true,
            pdlLeesahConsumerEnabled = true,
            personEnrichmentTaskDelay = "1m",
            personEnrichmentTaskEnabled = true,
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
