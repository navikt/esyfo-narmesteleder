package no.nav.syfo.narmestelederbehov.infrastructure

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.application.valkey.ValkeyCache
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.PersonDetails
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederbehov.domain.RegisteredName

interface PersonDetailsCache {
    fun get(personIdent: PersonIdent): PersonDetails?
    fun put(personIdent: PersonIdent, personDetails: PersonDetails)
}

class ValkeyPersonDetailsCache(private val valkeyCache: ValkeyCache) : PersonDetailsCache {
    override fun get(personIdent: PersonIdent): PersonDetails? {
        val entry = valkeyCache.get(key(personIdent), PersonDetailsCacheEntry::class.java)
        if (entry != null) COUNT_CACHE_HIT_PERSON_DETAILS.increment() else COUNT_CACHE_MISS_PERSON_DETAILS.increment()
        return entry?.toPersonDetails()
    }

    override fun put(personIdent: PersonIdent, personDetails: PersonDetails) {
        valkeyCache.put(key(personIdent), personDetails.toCacheEntry())
    }

    private fun key(personIdent: PersonIdent) = "$KEY_PREFIX-${personIdent.value}"

    companion object {
        const val KEY_PREFIX = "narmestelederbehov-person-v1"
    }
}

// Plain-string cache format keeps the stored JSON independent of the PersonIdent value class.
internal data class PersonDetailsCacheEntry(
    val personIdent: String,
    val firstName: String,
    val middleName: String?,
    val lastName: String,
    val registeredNames: List<RegisteredNameCacheEntry>,
)

internal data class RegisteredNameCacheEntry(
    val lastName: String,
    val middleName: String?,
)

internal fun PersonDetails.toCacheEntry() = PersonDetailsCacheEntry(
    personIdent = personIdent.value,
    firstName = name.firstName,
    middleName = name.middleName,
    lastName = name.lastName,
    registeredNames = name.registeredNames.map { RegisteredNameCacheEntry(it.lastName, it.middleName) },
)

internal fun PersonDetailsCacheEntry.toPersonDetails() = PersonDetails(
    personIdent = PersonIdent(personIdent),
    name = PersonNameDetails(
        firstName = firstName,
        middleName = middleName,
        lastName = lastName,
        registeredNames = registeredNames.map { RegisteredName(it.lastName, it.middleName) },
    ),
)

private val COUNT_CACHE_HIT_PERSON_DETAILS: Counter =
    Counter.builder("${METRICS_NS}_cache_hit_narmestelederbehov_person")
        .description("Counts cache hits when retrieving person details for narmestelederbehov from Valkey")
        .register(METRICS_REGISTRY)

private val COUNT_CACHE_MISS_PERSON_DETAILS: Counter =
    Counter.builder("${METRICS_NS}_cache_miss_narmestelederbehov_person")
        .description("Counts cache misses when retrieving person details for narmestelederbehov from Valkey")
        .register(METRICS_REGISTRY)
