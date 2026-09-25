package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup

class DinesykmeldteActiveSykmeldingLookup(
    private val dinesykmeldteService: DinesykmeldteService,
) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(
        personIdent: PersonIdent,
        organizationNumber: OrganizationNumber,
    ): Boolean = dinesykmeldteService.getIsActiveSykmelding(personIdent.value, organizationNumber.value)
}
