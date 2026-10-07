package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpiryRepository
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class ExpireNarmestelederbehovTaskTest :
    FunSpec({
        test("expires narmestelederbehov when the task executes") {
            val repository = RecordingExpiryRepository()
            val task = ExpireNarmestelederbehovTask(
                expireNarmestelederbehov = ExpireNarmestelederbehovUseCase(
                    repository = repository,
                    settings = NarmestelederbehovExpirySettings(daysAfterTom = 16),
                    clock = Clock.fixed(Instant.parse("2026-03-17T10:00:00Z"), ZoneOffset.UTC),
                    pauseBetweenBatches = Duration.ZERO,
                ),
                interval = 24.hours,
            )

            task.execute()

            repository.calls shouldBe listOf(LocalDate.parse("2026-03-01"))
        }
    })

private class RecordingExpiryRepository : NarmestelederbehovExpiryRepository {
    val calls = mutableListOf<LocalDate>()

    override suspend fun expireBehov(sykmeldingMaxDate: LocalDate, limit: Int): Int {
        calls += sykmeldingMaxDate
        return 0
    }
}
