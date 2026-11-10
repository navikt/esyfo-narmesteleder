package no.nav.syfo.platform.cache

data class ValkeySettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val ssl: Boolean,
) {
    override fun toString() = "ValkeySettings(host=$host, port=$port, username=$username, password=[REDACTED], ssl=$ssl)"
}
