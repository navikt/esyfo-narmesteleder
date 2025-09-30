package no.nav.syfo.application.auth

import no.nav.syfo.texas.client.OrganizationId

data class BrukerPrincipal(
    val ident: String? = null,
    val token: String,
    val consumer: OrganizationId? = null
)
