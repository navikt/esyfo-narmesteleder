package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.domain.Employee

class DinesykmeldteNarmestelederbehovActiveSykmeldingLookup(
    private val client: DinesykmeldteClient,
) : NarmestelederbehovActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(employee: Employee): Boolean = client.getIsActiveSykmelding(
        fnr = employee.personIdent.value,
        orgnummer = employee.organizationNumber.value,
    )
}
