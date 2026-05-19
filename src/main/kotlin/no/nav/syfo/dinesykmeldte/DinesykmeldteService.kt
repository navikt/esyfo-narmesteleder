package no.nav.syfo.dinesykmeldte

import no.nav.syfo.dinesykmeldte.client.IDinesykmeldteClient

class DinesykmeldteService(private val dinesykmeldteClient: IDinesykmeldteClient) : IDinesykmeldteService {
    override suspend fun getIsActiveSykmelding(
        personIdent: String,
        orgnummer: String
    ): Boolean = dinesykmeldteClient.getIsActiveSykmelding(
        fnr = personIdent,
        orgnummer = orgnummer
    )
}
