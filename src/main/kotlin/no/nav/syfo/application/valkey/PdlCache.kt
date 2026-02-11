package no.nav.syfo.application.valkey

import no.nav.syfo.pdl.Person

class PdlCache(
    private val valkeyCache: ValkeyCache,
) {

    fun getPerson(fnr: String): Person? {
        val person = valkeyCache.get("$PDL_CACHE_KEY_PREFIX-$fnr", Person::class.java)
        if (person != null) {
            COUNT_CACHE_HIT_DINE_SYKMELDTE.increment()
        }
        return person
    }

    fun putPerson(fnr: String, person: Person) {
        valkeyCache.put("$PDL_CACHE_KEY_PREFIX-$fnr", person)
    }

    companion object {
        const val PDL_CACHE_KEY_PREFIX = "pdl"
    }
}
