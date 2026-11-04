package no.nav.syfo.platform.auth.texas.altinn

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CachingAltinnTokenSourceTest :
    FunSpec({
        test("failure is cached within the TTL and retried after it") {
            val fixture = Fixture()
            fixture.delegate.results += failure()

            val first = fixture.source.token(TARGET)
            first.shouldBeInstanceOf<UpstreamResult.Failure>()
            val second = fixture.source.token(TARGET)
            fixture.advance(9.seconds)
            val third = fixture.source.token(TARGET)
            assertSoftly {
                second shouldBeSameInstanceAs first
                third shouldBeSameInstanceAs first
                fixture.delegate.calls.get() shouldBe 1
            }

            fixture.delegate.results += fixture.success(3600)
            fixture.advance(2.seconds)
            val retried = fixture.source.token(TARGET)
            assertSoftly {
                retried.shouldBeInstanceOf<UpstreamResult.Success<AltinnToken>>()
                fixture.delegate.calls.get() shouldBe 2
            }
        }

        test("valid token is cached and refreshed when 300 seconds or less remain") {
            val fixture = Fixture()
            fixture.delegate.results += fixture.success(1000)

            fixture.source.token(TARGET)
            fixture.source.token(TARGET)
            fixture.delegate.calls.get() shouldBe 1

            fixture.advance(700.seconds)
            fixture.delegate.results += fixture.success(3600)
            fixture.source.token(TARGET)
            fixture.delegate.calls.get() shouldBe 2
        }

        test("concurrent callers cause one delegate call while the failure is cached") {
            coroutineScope {
                val gate = CompletableDeferred<Unit>()
                val fixture = Fixture(FakeSource(gate))
                fixture.delegate.results += failure()

                val results = (1..20).map { async { fixture.source.token(TARGET) } }
                gate.complete(Unit)
                val all = results.awaitAll()

                fixture.delegate.calls.get() shouldBe 1
                all.forEach { it shouldBeSameInstanceAs all.first() }
            }
        }

        test("cancellation is propagated and not cached") {
            val fixture = Fixture()
            fixture.delegate.results += CancellationException("cancelled")

            shouldThrow<CancellationException> { fixture.source.token(TARGET) }
            fixture.delegate.results += fixture.success(3600)
            fixture.source.token(TARGET).shouldBeInstanceOf<UpstreamResult.Success<AltinnToken>>()
            fixture.delegate.calls.get() shouldBe 2
        }

        test("still-valid token is returned when refresh fails and the failure is not cached") {
            val fixture = Fixture()
            val initial = fixture.success(400)
            fixture.delegate.results += initial
            fixture.source.token(TARGET)

            fixture.advance(200.seconds)
            fixture.delegate.results += failure()
            val firstRefresh = fixture.source.token(TARGET)
            fixture.delegate.results += failure()
            val secondRefresh = fixture.source.token(TARGET)
            assertSoftly {
                firstRefresh shouldBe initial
                secondRefresh shouldBe initial
                fixture.delegate.calls.get() shouldBe 3
            }
        }

        test("failure for one target does not affect another") {
            val fixture = Fixture()
            fixture.delegate.results += failure()
            fixture.delegate.results += fixture.success(3600)

            val a = fixture.source.token("a")
            val b = fixture.source.token("b")
            assertSoftly {
                a.shouldBeInstanceOf<UpstreamResult.Failure>()
                b.shouldBeInstanceOf<UpstreamResult.Success<AltinnToken>>()
            }
        }
    })

private const val TARGET = "target"

private fun failure() = UpstreamResult.Failure(
    UpstreamFailure(ALTINN_AUTHENTICATION, UpstreamFailureStage.TOKEN_EXCHANGE, 503, IOException("down")),
)

private class Fixture(val delegate: FakeSource = FakeSource()) {
    val clock = MutableClock()
    val source = CachingAltinnTokenSource(delegate, clock, 10.seconds)

    fun advance(duration: Duration) = clock.advance(duration.inWholeMilliseconds)

    fun success(secondsLeft: Long) = UpstreamResult.Success(
        AltinnToken("token", clock.millis().milliseconds + secondsLeft.seconds),
    )
}

private class FakeSource(private val gate: CompletableDeferred<Unit>? = null) : AltinnTokenSource {
    val results = ArrayDeque<Any>()
    val calls = AtomicInteger()

    @Suppress("UNCHECKED_CAST")
    override suspend fun token(target: String): UpstreamResult<AltinnToken> {
        calls.incrementAndGet()
        gate?.await()
        val next = results.removeFirst()
        if (next is Throwable) throw next
        return next as UpstreamResult<AltinnToken>
    }
}

private class MutableClock(private var millis: Long = Instant.parse("2026-10-15T06:00:00Z").toEpochMilli()) : Clock() {
    fun advance(ms: Long) {
        millis += ms
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
}
