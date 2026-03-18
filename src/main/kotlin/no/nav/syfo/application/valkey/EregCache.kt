package no.nav.syfo.application.valkey

import no.nav.syfo.ereg.client.Organisasjon

class EregCache(
    private val valkeyCache: ValkeyCache,
) {

    fun getOrganisasjon(orgnummer: String): Organisasjon? {
        val organisasjon = valkeyCache.get("$EREG_CACHE_KEY_PREFIX-$orgnummer", type = Organisasjon::class.java)
        if (organisasjon != null) {
            COUNT_CACHE_HIT_PDL_GET_PERSON.increment()
        } else {
            COUNT_CACHE_MISS_PDL_GET_PERSON.increment()
        }
        return organisasjon
    }

    fun putOrganisasjon(orgnummer: String, organisasjon: Organisasjon?) {
        valkeyCache.put("$EREG_CACHE_KEY_PREFIX-$orgnummer", organisasjon)
    }

    companion object {
        const val EREG_CACHE_KEY_PREFIX =
            "ereg_v2_h_ol" // Key for ereg v2 response with hierarchy and organisasjonsledd
    }
}
