package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.application.PersonLookupResult
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

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
            val delegate = RecordingPersonLookup(result = PersonLookupResult.NotFound)

            CachedPersonLookup(delegate, cache).find(personIdent) shouldBe PersonLookupResult.Found(person)

            delegate.lookups.shouldBeEmpty()
        }

        test("looks up and caches the person on cache miss") {
            val cache = FakePersonDetailsCache()
            val delegate = RecordingPersonLookup(result = PersonLookupResult.Found(person))

            CachedPersonLookup(delegate, cache).find(personIdent) shouldBe PersonLookupResult.Found(person)

            delegate.lookups shouldContainExactly listOf(personIdent)
            cache.entries shouldBe mapOf(personIdent to person)
        }

        test("does not cache a person that was not found") {
            val cache = FakePersonDetailsCache()

            CachedPersonLookup(RecordingPersonLookup(result = PersonLookupResult.NotFound), cache).find(personIdent) shouldBe PersonLookupResult.NotFound

            cache.entries shouldBe emptyMap()
        }

        test("cache entry survives a JSON round trip") {
            val mapper = jacksonMapper()
            val json = mapper.writeValueAsString(person.toCacheEntry())

            mapper.readValue(json, PersonDetailsCacheEntry::class.java).toPersonDetails() shouldBe person
        }

        test("does not cache Unavailable and retries the delegate on the next lookup") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            val result = PersonLookupResult.Unavailable(failure)
            val cache = FakePersonDetailsCache()
            val delegate = RecordingPersonLookup(result)
            val lookup = CachedPersonLookup(delegate, cache)
            repeat(2) { lookup.find(personIdent) shouldBe result }
            cache.entries shouldBe emptyMap()
            delegate.lookups shouldContainExactly listOf(personIdent, personIdent)
        }
    })

private class FakePersonDetailsCache(initial: Map<PersonIdent, PersonDetails> = emptyMap()) : PersonDetailsCache {
    val entries = initial.toMutableMap()
    override fun get(personIdent: PersonIdent): PersonDetails? = entries[personIdent]
    override fun put(personIdent: PersonIdent, personDetails: PersonDetails) {
        entries[personIdent] = personDetails
    }
}

private class RecordingPersonLookup(private val result: PersonLookupResult) : PersonLookup {
    val lookups = mutableListOf<PersonIdent>()
    override suspend fun find(personIdent: PersonIdent): PersonLookupResult {
        lookups += personIdent
        return result
    }
}
