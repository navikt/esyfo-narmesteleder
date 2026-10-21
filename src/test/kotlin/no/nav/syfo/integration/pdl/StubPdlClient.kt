package no.nav.syfo.integration.pdl

import no.nav.syfo.platform.upstream.UpstreamResult

/** PDL client stub for single-person lookups that answers [result] and records the requested idents. */
internal class StubPdlClient(private val result: UpstreamResult<PdlPerson?>) : PdlClient {
    val requests = mutableListOf<String>()

    override suspend fun getPerson(ident: String): UpstreamResult<PdlPerson?> {
        requests += ident
        return result
    }

    override suspend fun getPersonBolk(idents: List<String>): UpstreamResult<GetPersonBolkResponse> = error("Not used")
}
