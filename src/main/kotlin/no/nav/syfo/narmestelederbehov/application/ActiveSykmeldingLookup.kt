package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

fun interface ActiveSykmeldingLookup {
    suspend fun hasActiveSykmelding(
        personIdent: PersonIdent,
        organizationNumber: OrganizationNumber,
    ): Boolean
}
