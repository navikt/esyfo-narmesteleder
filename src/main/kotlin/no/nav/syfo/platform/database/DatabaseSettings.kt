package no.nav.syfo.platform.database

data class DatabaseSettings(
    val host: String,
    val port: String,
    val name: String,
    val username: String,
    val password: String,
    val security: DatabaseSecurity,
) {
    fun jdbcUrl(): String {
        val sslSuffix = when (security) {
            DatabaseSecurity.Plaintext -> ""
            is DatabaseSecurity.Ssl -> with(security) {
                "?ssl=on&sslrootcert=$rootCert&sslcert=$cert&sslmode=$mode&sslkey=$key"
            }
        }
        return "jdbc:postgresql://$host:$port/$name$sslSuffix"
    }

    override fun toString() = "DatabaseSettings(host=$host, port=$port, name=$name, username=$username, " +
        "password=[REDACTED], security=$security)"
}

sealed interface DatabaseSecurity {
    /** Local Postgres without TLS. */
    data object Plaintext : DatabaseSecurity

    /** Paths to the certificate files NAIS mounts for Cloud SQL, and the SSL mode. */
    data class Ssl(
        val cert: String,
        val key: String,
        val rootCert: String,
        val mode: String,
    ) : DatabaseSecurity
}
