package no.nav.syfo.application.valkey

data class ValkeyEnvironment(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val ssl: Boolean = true,
)
