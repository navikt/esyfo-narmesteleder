package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

fun interface HasActiveNarmestelederrelasjon {
    suspend fun execute(employeeIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber): Boolean
}
