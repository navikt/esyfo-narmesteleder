package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.ActiveSykmeldingLookup

class DinesykmeldteActiveSykmeldingLookup(private val service: DinesykmeldteService) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(personIdent: PersonIdent, organizationNumber: OrganizationNumber) = service.getIsActiveSykmelding(personIdent.value, organizationNumber.value)
}
