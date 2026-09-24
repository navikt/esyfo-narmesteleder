package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.dinesykmeldte.IDinesykmeldteService
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup

class DinesykmeldteActiveSykmeldingLookup(
    private val dinesykmeldteService: IDinesykmeldteService,
) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(
        personIdent: PersonIdent,
        organizationNumber: OrganizationNumber,
    ): Boolean = dinesykmeldteService.getIsActiveSykmelding(personIdent.value, organizationNumber.value)
}
