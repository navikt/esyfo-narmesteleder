package no.nav.syfo.narmestelederbehov.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ExpireNarmestelederbehovUseCaseTest :
    FunSpec({
        val clock = Clock.fixed(Instant.parse("2026-03-17T10:00:00Z"), ZoneOffset.UTC)
        val settings = NarmestelederbehovExpirySettings(daysAfterTom = 16)

        test("expires batches with tom before today minus the configured days until a batch is empty") {
            val repository = RecordingExpiryRepository(listOf(500, 500, 300, 0))

            runTest {
                ExpireNarmestelederbehovUseCase(repository, settings, clock).execute()

                currentTime shouldBe 4 * 500L
            }

            repository.calls shouldBe List(4) { LocalDate.parse("2026-03-01") to 500 }
        }

        test("runs one batch and pauses once when nothing is expired") {
            val repository = RecordingExpiryRepository(listOf(0))

            runTest {
                ExpireNarmestelederbehovUseCase(repository, settings, clock).execute()

                currentTime shouldBe 500L
            }

            repository.calls shouldBe listOf(LocalDate.parse("2026-03-01") to 500)
        }

        test("uses the configured number of days after tom") {
            val repository = RecordingExpiryRepository(listOf(0))

            runTest {
                ExpireNarmestelederbehovUseCase(repository, NarmestelederbehovExpirySettings(daysAfterTom = 0), clock).execute()
            }

            repository.calls.single().first shouldBe LocalDate.parse("2026-03-17")
        }
    })

private class RecordingExpiryRepository(results: List<Int>) : NarmestelederbehovExpiryRepository {
    private val results = ArrayDeque(results)
    val calls = mutableListOf<Pair<LocalDate, Int>>()

    override suspend fun expireOpenWithSykmeldingTomBefore(tomBefore: LocalDate, limit: Int): Int {
        calls += tomBefore to limit
        return results.removeFirst()
    }
}
