package no.nav.syfo.dinesykmeldte.client

class FakeDinesykmeldteClient : DinesykmeldteClient {
    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean = true
}
