package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.PersonDetails
import no.nav.syfo.narmestelederbehov.application.PersonLookup
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederbehov.domain.RegisteredName

class CachedPersonLookupTest :
    FunSpec({
        val personIdent = PersonIdent("12345678901")
        val person = PersonDetails(
            personIdent,
            PersonNameDetails(
                firstName = "Ola",
                middleName = "Mellom",
                lastName = "Nordmann",
                registeredNames = listOf(RegisteredName("Nordmann", "Mellom"), RegisteredName("Tidligere", null)),
            ),
        )

        test("returns cached person without calling the delegate") {
            val cache = FakePersonDetailsCache(mapOf(personIdent to person))
            val delegate = RecordingPersonLookup(result = null)

            CachedPersonLookup(delegate, cache).find(personIdent) shouldBe person

            delegate.lookups.shouldBeEmpty()
        }

        test("looks up and caches the person on cache miss") {
            val cache = FakePersonDetailsCache()
            val delegate = RecordingPersonLookup(result = person)

            CachedPersonLookup(delegate, cache).find(personIdent) shouldBe person

            delegate.lookups shouldContainExactly listOf(personIdent)
            cache.entries shouldBe mapOf(personIdent to person)
        }

        test("does not cache a person that was not found") {
            val cache = FakePersonDetailsCache()

            CachedPersonLookup(RecordingPersonLookup(result = null), cache).find(personIdent).shouldBeNull()

            cache.entries shouldBe emptyMap()
        }

        test("cache entry survives a JSON round trip") {
            val mapper = jacksonMapper()
            val json = mapper.writeValueAsString(person.toCacheEntry())

            mapper.readValue(json, PersonDetailsCacheEntry::class.java).toPersonDetails() shouldBe person
        }
    })

private class FakePersonDetailsCache(initial: Map<PersonIdent, PersonDetails> = emptyMap()) : PersonDetailsCache {
    val entries = initial.toMutableMap()
    override fun get(personIdent: PersonIdent): PersonDetails? = entries[personIdent]
    override fun put(personIdent: PersonIdent, personDetails: PersonDetails) {
        entries[personIdent] = personDetails
    }
}

private class RecordingPersonLookup(private val result: PersonDetails?) : PersonLookup {
    val lookups = mutableListOf<PersonIdent>()
    override suspend fun find(personIdent: PersonIdent): PersonDetails? {
        lookups += personIdent
        return result
    }
}
