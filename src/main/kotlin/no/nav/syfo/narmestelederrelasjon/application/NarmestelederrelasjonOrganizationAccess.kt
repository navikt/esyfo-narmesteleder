package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.application.auth.Principal

interface NarmestelederrelasjonOrganizationAccess {
    suspend fun hasAccess(
        principal: Principal,
        orgNumber: String,
    ): Boolean
}
