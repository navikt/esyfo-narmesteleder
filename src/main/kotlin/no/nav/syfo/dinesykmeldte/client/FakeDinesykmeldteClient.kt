package no.nav.syfo.dinesykmeldte.client

class FakeDinesykmeldteClient() : IDinesykmeldteClient {
    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean = true
}
