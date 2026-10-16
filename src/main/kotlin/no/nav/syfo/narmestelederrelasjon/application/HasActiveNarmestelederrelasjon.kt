package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

fun interface HasActiveNarmestelederrelasjon {
    suspend fun execute(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber): Boolean
}
