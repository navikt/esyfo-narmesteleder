package no.nav.syfo.maintenance

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.environment.UpdateDialogportenTaskProperties
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpiryRepository
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MaintenanceTaskTest :
    DescribeSpec({
        val clock = Clock.fixed(Instant.parse("2026-03-17T10:00:00Z"), ZoneOffset.UTC)
        val deleteOldSykmeldinger = mockk<DeleteOldSykmeldinger>()

        val env = OtherEnvironmentProperties(
            electorPath = "elector",
            electorSSEUrl = "not.applicable",
            frontendBaseUrl = "https://frontend.test.nav.no",
            publicIngressUrl = "https://test.nav.no",
            persistLeesahNlBehov = true,
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
            isDialogportenBackgroundTaskEnabled = true,
            daysAfterTomToExpireBehovs = 16,
            maintenanceTaskDelay = "100ms",
            persistSendtSykmelding = true,
            maintenanceTaskEnabled = true,
            persistNarmestelederRegister = false,
            pdlLeesahConsumerEnabled = false,
            personEnrichmentTaskDelay = "5m",
            personEnrichmentTaskEnabled = false,
        )

        fun createTask(expiryRepository: NarmestelederbehovExpiryRepository) = MaintenanceTask(
            expireNarmestelederbehov = ExpireNarmestelederbehovUseCase(
                repository = expiryRepository,
                settings = NarmestelederbehovExpirySettings(daysAfterTom = 16),
                clock = clock,
                pauseBetweenBatches = Duration.ZERO,
            ),
            deleteOldSykmeldinger = deleteOldSykmeldinger,
            env = env,
        )

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }

        describe("MaintenanceTask") {
            context("execute") {
                it("should expire narmestelederbehov when the scheduled task runs") {
                    val expiryRepository = RecordingExpiryRepository()
                    coEvery { deleteOldSykmeldinger.execute() } just Runs

                    val job = launch {
                        createTask(expiryRepository).runTask()
                    }

                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    expiryRepository.calls.shouldNotBeEmpty()
                    expiryRepository.calls.first() shouldBe LocalDate.parse("2026-03-01")
                }

                it("expires behov before deleting old sykmeldinger") {
                    val expiryRepository = RecordingExpiryRepository()
                    coEvery { deleteOldSykmeldinger.execute() } answers {
                        expiryRepository.calls.size shouldBe 1
                    }

                    createTask(expiryRepository).execute()

                    coVerify(exactly = 1) { deleteOldSykmeldinger.execute() }
                }

                it("does not delete sykmeldinger when behov expiration fails") {
                    val failingRepository = object : NarmestelederbehovExpiryRepository {
                        override suspend fun expireOpenWithSykmeldingTomBefore(tomBefore: LocalDate, limit: Int): Int = throw IllegalStateException("failure")
                    }

                    shouldThrow<IllegalStateException> {
                        createTask(failingRepository).execute()
                    }

                    coVerify(exactly = 0) { deleteOldSykmeldinger.execute() }
                }
            }
        }
    })

private class RecordingExpiryRepository : NarmestelederbehovExpiryRepository {
    val calls = mutableListOf<LocalDate>()

    override suspend fun expireOpenWithSykmeldingTomBefore(tomBefore: LocalDate, limit: Int): Int {
        calls += tomBefore
        return 0
    }
}
