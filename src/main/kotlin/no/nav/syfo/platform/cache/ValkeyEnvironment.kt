package no.nav.syfo.platform.cache

import no.nav.syfo.platform.config.getEnvVar

data class ValkeyEnvironment(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val ssl: Boolean = true,
) {
    companion object {
        fun createFromEnvVars(): ValkeyEnvironment = ValkeyEnvironment(
            host = getEnvVar("VALKEY_HOST_FELLES_CACHE"),
            port = getEnvVar("VALKEY_PORT_FELLES_CACHE").toInt(),
            username = getEnvVar("VALKEY_USERNAME_FELLES_CACHE"),
            password = getEnvVar("VALKEY_PASSWORD_FELLES_CACHE")
        )

        fun createForLocal(): ValkeyEnvironment = ValkeyEnvironment(
            host = "localhost",
            port = 6379,
            username = "default",
            password = "test",
            ssl = false
        )
    }
}
