package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup

class DinesykmeldteActiveSykmeldingLookup(
    private val client: DinesykmeldteClient,
) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(
        personIdent: PersonIdent,
        organizationNumber: OrganizationNumber,
    ): Boolean = client.getIsActiveSykmelding(fnr = personIdent.value, orgnummer = organizationNumber.value)
}
