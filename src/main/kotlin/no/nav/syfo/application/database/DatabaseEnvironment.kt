
package no.nav.syfo.application.database

import no.nav.syfo.application.environment.NAIS_DATABASE_ENV_PREFIX
import no.nav.syfo.application.environment.getEnvVar

data class DatabaseEnvironment(
    val host: String,
    val port: String,
    val name: String,
    val username: String,
    val password: String,
    val sslcert: String?,
    val sslkey: String,
    val sslrootcert: String,
    val sslmode: String,
) {
    fun jdbcUrl(): String {
        val sslsuffix = if (sslcert == null) {
            ""
        } else {
            "?ssl=on&sslrootcert=$sslrootcert&sslcert=$sslcert&sslmode=$sslmode&sslkey=$sslkey"
        }

        val url = "jdbc:postgresql://$host:$port/$name$sslsuffix"
        return url
    }

    companion object {
        fun createForLocal(): DatabaseEnvironment = DatabaseEnvironment(
            host = "localhost",
            port = "5432",
            name = "esyfo-narmesteleder_dev",
            username = "username",
            password = "password",
            sslcert = null,
            sslkey = "",
            sslrootcert = "",
            sslmode = "",
        )

        fun createFromEnvVars() = DatabaseEnvironment(
            host = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_HOST"),
            port = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_PORT"),
            name = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_DATABASE"),
            username = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_USERNAME"),
            password = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_PASSWORD"),
            sslcert = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_SSLCERT"),
            sslkey = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_SSLKEY_PK8"),
            sslrootcert = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_SSLROOTCERT"),
            sslmode = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_SSLMODE"),
        )
    }
}
