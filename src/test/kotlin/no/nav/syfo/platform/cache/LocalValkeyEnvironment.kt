package no.nav.syfo.platform.cache

fun ValkeyEnvironment.Companion.createForLocal(): ValkeyEnvironment = ValkeyEnvironment(
    host = "localhost",
    port = 6379,
    username = "default",
    password = "test",
    ssl = false
)
