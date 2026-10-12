package no.nav.syfo.integration.ereg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.syfo.application.valkey.ValkeyCache
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult

class CachedEregClientTest :
    FunSpec({
        val orgNumber = "910000001"
        val organization = Organisasjon(orgNumber, Navn(sammensattnavn = "Organization"))
        val key = "${EregCache.EREG_CACHE_KEY_PREFIX}-$orgNumber"

        test("cache hit skips the delegate") {
            val fixture = CacheFixture()
            fixture.entries[key] = organization
            val delegate = RecordingEregClient { error("Cache hit must not call delegate") }

            CachedEregClient(delegate, fixture.cache).getOrganisasjon(orgNumber) shouldBe UpstreamResult.Success(organization)
            delegate.requests shouldBe emptyList()
            verify(exactly = 0) { fixture.valkey.put(any(), any<Organisasjon>(), any()) }
        }

        test("cache miss calls the delegate and caches a non-null result") {
            val fixture = CacheFixture()
            val result = UpstreamResult.Success(organization)
            val delegate = RecordingEregClient { result }
            val client = CachedEregClient(delegate, fixture.cache)

            client.getOrganisasjon(orgNumber) shouldBeSameInstanceAs result
            fixture.entries shouldBe mapOf(key to organization)
            client.getOrganisasjon(orgNumber) shouldBe UpstreamResult.Success(organization)
            delegate.requests shouldBe listOf(orgNumber)
            verify(exactly = 1) { fixture.valkey.put(key, organization, ValkeyCache.CACHE_TTL_SECONDS) }
        }

        test("Success(null) is returned unchanged and not cached") {
            val fixture = CacheFixture()
            val result = UpstreamResult.Success(null)
            val delegate = RecordingEregClient { result }
            val client = CachedEregClient(delegate, fixture.cache)

            client.getOrganisasjon(orgNumber) shouldBeSameInstanceAs result
            client.getOrganisasjon(orgNumber) shouldBeSameInstanceAs result
            delegate.requests shouldBe listOf(orgNumber, orgNumber)
            fixture.entries shouldBe emptyMap()
            verify(exactly = 0) { fixture.valkey.put(any(), any<Organisasjon>(), any()) }
        }

        test("Failure is returned unchanged and not cached") {
            val fixture = CacheFixture()
            val result = UpstreamResult.Failure(UpstreamFailure(EREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException()))
            val delegate = RecordingEregClient { result }
            val client = CachedEregClient(delegate, fixture.cache)

            client.getOrganisasjon(orgNumber) shouldBeSameInstanceAs result
            client.getOrganisasjon(orgNumber) shouldBeSameInstanceAs result
            delegate.requests shouldBe listOf(orgNumber, orgNumber)
            fixture.entries shouldBe emptyMap()
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

private class RecordingEregClient(private val result: () -> UpstreamResult<Organisasjon?>) : EregClient {
    val requests = mutableListOf<String>()
    override suspend fun getOrganisasjon(orgnummer: String): UpstreamResult<Organisasjon?> {
        requests += orgnummer
        return result()
    }
}
