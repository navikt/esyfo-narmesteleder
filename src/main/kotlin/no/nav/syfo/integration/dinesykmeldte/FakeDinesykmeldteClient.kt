package no.nav.syfo.integration.dinesykmeldte

import no.nav.syfo.platform.upstream.UpstreamResult

class FakeDinesykmeldteClient : DinesykmeldteClient {
    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): UpstreamResult<Boolean> = UpstreamResult.Success(true)
}
