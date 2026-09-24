package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber

interface NarmestelederrelasjonOrganization {
    suspend fun findName(orgNumber: OrganizationNumber): String?
}
