package no.nav.syfo.integration.ereg

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.application.valkey.ValkeyCache

class CachedEregClientTest :
    FunSpec({
        val orgNumber = "910000001"
        val organization = Organisasjon(orgNumber, Navn(sammensattnavn = "Organization"))
        val key = "${EregCache.EREG_CACHE_KEY_PREFIX}-$orgNumber"

        test("cache hit skips the delegate") {
            val fixture = CacheFixture()
            fixture.entries[key] = organization
            val delegate = RecordingEregClient { error("Cache hit must not call delegate") }

            CachedEregClient(delegate, fixture.cache).getOrganisasjon(orgNumber) shouldBe organization
            delegate.requests shouldBe emptyList()
            verify(exactly = 0) { fixture.valkey.put(any(), any<Organisasjon>(), any()) }
        }

        test("cache miss calls the delegate and caches a non-null result") {
            val fixture = CacheFixture()
            val delegate = RecordingEregClient { organization }
            val client = CachedEregClient(delegate, fixture.cache)

            client.getOrganisasjon(orgNumber) shouldBe organization
            fixture.entries shouldBe mapOf(key to organization)
            client.getOrganisasjon(orgNumber) shouldBe organization
            delegate.requests shouldBe listOf(orgNumber)
            verify(exactly = 1) { fixture.valkey.put(key, organization, ValkeyCache.CACHE_TTL_SECONDS) }
        }

        test("null results are not cached") {
            val fixture = CacheFixture()
            val delegate = RecordingEregClient { null }
            val client = CachedEregClient(delegate, fixture.cache)

            client.getOrganisasjon(orgNumber) shouldBe null
            client.getOrganisasjon(orgNumber) shouldBe null
            delegate.requests shouldBe listOf(orgNumber, orgNumber)
            fixture.entries shouldBe emptyMap()
            verify(exactly = 0) { fixture.valkey.put(any(), any<Organisasjon>(), any()) }
        }

        test("upstream exceptions propagate unchanged without caching") {
            val fixture = CacheFixture()
            val failure = UpstreamRequestException("Ereg unavailable")
            val delegate = RecordingEregClient { throw failure }

            shouldThrow<UpstreamRequestException> {
                CachedEregClient(delegate, fixture.cache).getOrganisasjon(orgNumber)
            } shouldBe failure
            delegate.requests shouldBe listOf(orgNumber)
            verify(exactly = 0) { fixture.valkey.put(any(), any<Organisasjon>(), any()) }
        }
    })

private class CacheFixture {
    val entries = mutableMapOf<String, Organisasjon>()
    val valkey = mockk<ValkeyCache> {
        every { get(any(), Organisasjon::class.java) } answers { entries[firstArg()] }
        every { put(any(), any<Organisasjon>(), any()) } answers {
            entries[firstArg()] = secondArg()
        }
    }
    val cache = EregCache(valkey)
}

private class RecordingEregClient(private val result: () -> Organisasjon?) : EregClient {
    val requests = mutableListOf<String>()
    override suspend fun getOrganisasjon(orgnummer: String): Organisasjon? {
        requests += orgnummer
        return result()
    }
}
