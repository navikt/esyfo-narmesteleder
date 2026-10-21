package no.nav.syfo.integration.ereg

import no.nav.syfo.platform.upstream.UpstreamResult

/** Ereg client stub that answers with [result] and records the requested organization numbers. */
internal class RecordingEregClient(private val result: suspend (String) -> UpstreamResult<Organisasjon?>) : EregClient {
    val requests = mutableListOf<String>()

    override suspend fun getOrganisasjon(orgnummer: String): UpstreamResult<Organisasjon?> {
        requests += orgnummer
        return result(orgnummer)
    }
}
