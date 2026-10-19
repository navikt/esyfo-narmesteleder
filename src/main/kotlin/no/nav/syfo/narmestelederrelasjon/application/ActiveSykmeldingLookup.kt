package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

fun interface ActiveSykmeldingLookup {
    suspend fun hasActiveSykmelding(
        personIdent: PersonalIdentificationNumber,
        organizationNumber: OrganizationNumber,
    ): Boolean
}
