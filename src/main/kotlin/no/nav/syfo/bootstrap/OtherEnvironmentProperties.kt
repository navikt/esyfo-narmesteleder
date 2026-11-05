package no.nav.syfo.bootstrap

import no.nav.syfo.platform.config.getEnvVar

data class OtherEnvironmentProperties(
    val frontendBaseUrl: String,
    val publicIngressUrl: String,
    val updateDialogportenTaskProperties: UpdateDialogportenTaskProperties,
    val persistLeesahNlBehov: Boolean,
    val persistSendtSykmelding: Boolean,
    val persistNarmestelederRegister: Boolean,
    val pdlLeesahConsumerEnabled: Boolean,
    val employmentCheckEnabled: Boolean,
    val employmentCheckObserveSourceEnabled: Boolean,
    val employmentCheckRevokeEnabled: Boolean = false,
) {
    init {
        require(!(employmentCheckRevokeEnabled && employmentCheckObserveSourceEnabled)) {
            "EMPLOYMENT_CHECK_REVOKE_ENABLED and EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED cannot both be enabled"
        }
    }

    companion object {
        fun createFromEnvVars() = OtherEnvironmentProperties(
            frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
            publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
            persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "true").toBoolean(),
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createFromEnvVars(),
            persistSendtSykmelding = getEnvVar("PERSIST_SENDT_SYKMELDING", "false").toBoolean(),
            persistNarmestelederRegister = getEnvVar("PERSIST_NARMESTELEDER_REGISTER", "false").toBoolean(),
            pdlLeesahConsumerEnabled = getEnvVar("PDL_LEESAH_CONSUMER_ENABLED", "false").toBoolean(),
            employmentCheckEnabled = getEnvVar("EMPLOYMENT_CHECK_ENABLED", "false").toBoolean(),
            employmentCheckObserveSourceEnabled = getEnvVar("EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED", "false").toBoolean(),
            employmentCheckRevokeEnabled = getEnvVar("EMPLOYMENT_CHECK_REVOKE_ENABLED", "false").toBoolean(),
        )

        fun createForLocal() = OtherEnvironmentProperties(
            frontendBaseUrl = "http://localhost:3000",
            publicIngressUrl = "http://localhost:8080",
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
            persistLeesahNlBehov = true,
            persistSendtSykmelding = true,
            persistNarmestelederRegister = true,
            pdlLeesahConsumerEnabled = true,
            employmentCheckEnabled = false,
            employmentCheckObserveSourceEnabled = false,
            employmentCheckRevokeEnabled = false,
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
