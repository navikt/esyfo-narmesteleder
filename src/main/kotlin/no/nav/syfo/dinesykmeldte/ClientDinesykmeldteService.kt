package no.nav.syfo.dinesykmeldte

import no.nav.syfo.dinesykmeldte.client.DinesykmeldteClient

class ClientDinesykmeldteService(private val dinesykmeldteClient: DinesykmeldteClient) : DinesykmeldteService {
    override suspend fun getIsActiveSykmelding(
        personIdent: String,
        orgnummer: String
    ): Boolean = dinesykmeldteClient.getIsActiveSykmelding(
        fnr = personIdent,
        orgnummer = orgnummer
    )
}
