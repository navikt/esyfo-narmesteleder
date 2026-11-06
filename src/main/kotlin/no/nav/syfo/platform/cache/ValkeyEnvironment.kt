package no.nav.syfo.platform.cache

data class ValkeyEnvironment(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val ssl: Boolean,
) {
    override fun toString() = "ValkeyEnvironment(host=$host, port=$port, username=$username, password=[REDACTED], ssl=$ssl)"
}
