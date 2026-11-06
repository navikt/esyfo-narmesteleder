package no.nav.syfo.platform.database

fun DatabaseEnvironment.Companion.createForLocal(): DatabaseEnvironment = DatabaseEnvironment(
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
