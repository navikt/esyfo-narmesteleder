package no.nav.syfo.integration.dinesykmeldte

class FakeDinesykmeldteClient : DinesykmeldteClient {
    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean = true
}
