package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.narmestelederbehov.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.domain.Employee

class DinesykmeldteActiveSykmeldingLookup(
    private val client: DinesykmeldteClient,
) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(employee: Employee): Boolean = client.getIsActiveSykmelding(
        fnr = employee.personIdent.value,
        orgnummer = employee.organizationNumber.value,
    )
}
