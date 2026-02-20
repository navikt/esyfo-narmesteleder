package no.nav.syfo.application.environment

data class OtherEnvironmentProperties(
    val electorPath: String,
    val frontendBaseUrl: String,
    val publicIngressUrl: String,
    val updateDialogportenTaskProperties: UpdateDialogportenTaskProperties,
    val maintenanceTaskDelay: String,
    val deleteDialogportenDialogsTaskProperties: DeleteDialogportenDialogsTaskProperties,
    val persistLeesahNlBehov: Boolean,
    val isDialogportenBackgroundTaskEnabled: Boolean,
    val dialogportenIsApiOnly: Boolean,
    val daysAfterTomToExpireBehovs: Long,
    val persistSendtSykmelding: Boolean,
    val maintenanceTaskEnabled: Boolean,
) {
    companion object {
        fun createFromEnvVars() = OtherEnvironmentProperties(
            electorPath = getEnvVar("ELECTOR_PATH"),
            frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
            publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
            persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "true").toBoolean(),
            isDialogportenBackgroundTaskEnabled = getEnvVar("DIALOGPORTEN_TASK_ENABLED").toBoolean(),
            dialogportenIsApiOnly = getEnvVar("DIALOGPORTEN_API_ONLY").toBoolean(),
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createFromEnvVars(),
            deleteDialogportenDialogsTaskProperties = DeleteDialogportenDialogsTaskProperties.createFromEnvVars(),
            persistSendtSykmelding = getEnvVar("PERSIST_SENDT_SYKMELDING", "false").toBoolean(),
            daysAfterTomToExpireBehovs = getEnvVar(
                "DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS",
                "16"
            ).toLong(),
            maintenanceTaskDelay = getEnvVar("MAINTENANCE_TASK_DELAY", "24h"),
            maintenanceTaskEnabled = getEnvVar("BEHOV_MAINTENANCE_TASK_ENABLED", "false").toBoolean(),
        )

        fun createForLocal() = OtherEnvironmentProperties(
            electorPath = "esyfo-narmesteleder",
            frontendBaseUrl = "http://localhost:3000",
            publicIngressUrl = "http://localhost:8080",
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
            persistLeesahNlBehov = true,
            isDialogportenBackgroundTaskEnabled = true,
            dialogportenIsApiOnly = false,
            deleteDialogportenDialogsTaskProperties = DeleteDialogportenDialogsTaskProperties.createForLocal(),
            persistSendtSykmelding = true,
            daysAfterTomToExpireBehovs = 0,
            maintenanceTaskDelay = "1m",
            maintenanceTaskEnabled = true
        )
    }
}

data class DeleteDialogportenDialogsTaskProperties(
    val pollingDelay: String,
    val deleteDialogerTaskEnabled: Boolean,
    val deleteDialogerLimit: Int = 100,
    val deleteDialogerSleepAfterPage: Long = 5000,
    val resendDialogTaskEnabled: Boolean,
) {
    companion object {
        fun createFromEnvVars() = DeleteDialogportenDialogsTaskProperties(
            pollingDelay = getEnvVar("DIALOGPORTEN_DELETE_DIALOGER_POLLING_DELAY", "5m"),
            deleteDialogerLimit = getEnvVar("DIALOGPORTEN_DELETE_DIALOGER_PAGE_LIMIT", "100").toInt(),
            deleteDialogerSleepAfterPage = getEnvVar("DIALOGPORTEN_DELETE_DIALOGER_SLEEP_AFTER_PAGE", "5").toLong(),
            deleteDialogerTaskEnabled = getEnvVar("DIALOGPORTEN_DELETE_DIALOGER_TASK_ENABLED", "false").toBoolean(),
            resendDialogTaskEnabled = getEnvVar("DIALOGPORTEN_RESEND_DIALOGER_TASK_ENABLED", "false").toBoolean(),
        )

        fun createForLocal() = DeleteDialogportenDialogsTaskProperties(
            pollingDelay = "30s",
            deleteDialogerLimit = 3,
            deleteDialogerSleepAfterPage = 2000L,
            deleteDialogerTaskEnabled = false,
            resendDialogTaskEnabled = true,
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
