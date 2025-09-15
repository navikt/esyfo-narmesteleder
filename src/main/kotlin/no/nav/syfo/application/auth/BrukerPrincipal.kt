package no.nav.syfo.application.auth

data class BrukerPrincipal(
    val ident: String,
    val token: String,
)
