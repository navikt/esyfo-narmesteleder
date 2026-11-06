package no.nav.syfo.platform.database

data class DatabaseEnvironment(
    val host: String,
    val port: String,
    val name: String,
    val username: String,
    val password: String,
    /** Null only for local runs, where Postgres has no TLS. */
    val ssl: DatabaseSsl?,
) {
    fun jdbcUrl(): String {
        val sslSuffix = ssl?.run { "?ssl=on&sslrootcert=$rootCert&sslcert=$cert&sslmode=$mode&sslkey=$key" }.orEmpty()
        return "jdbc:postgresql://$host:$port/$name$sslSuffix"
    }

    override fun toString() = "DatabaseEnvironment(host=$host, port=$port, name=$name, username=$username, " +
        "password=[REDACTED], ssl=$ssl)"
}

/** Paths to the certificate files NAIS mounts for Cloud SQL, and the SSL mode. */
data class DatabaseSsl(
    val cert: String,
    val key: String,
    val rootCert: String,
    val mode: String,
)
