package no.nav.syfo.integration.ereg

import no.nav.syfo.platform.upstream.UpstreamResult

class CachedEregClient(
    private val delegate: EregClient,
    private val cache: EregCache,
) : EregClient {
    override suspend fun getOrganisasjon(orgnummer: String): UpstreamResult<Organisasjon?> {
        cache.getOrganisasjon(orgnummer)?.let { return UpstreamResult.Success(it) }
        val result = delegate.getOrganisasjon(orgnummer)
        if (result is UpstreamResult.Success) {
            result.value?.let { cache.putOrganisasjon(orgnummer, it) }
        }
        return result
    }
}
