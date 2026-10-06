package no.nav.syfo.integration.ereg

class CachedEregClient(
    private val delegate: EregClient,
    private val cache: EregCache,
) : EregClient {
    override suspend fun getOrganisasjon(orgnummer: String): Organisasjon? {
        cache.getOrganisasjon(orgnummer)?.let { return it }
        return delegate.getOrganisasjon(orgnummer)?.also { cache.putOrganisasjon(orgnummer, it) }
    }
}
