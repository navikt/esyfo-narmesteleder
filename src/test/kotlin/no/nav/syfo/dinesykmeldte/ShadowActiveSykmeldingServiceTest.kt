package no.nav.syfo.dinesykmeldte

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import no.nav.syfo.sykmelding.exposed.IActiveSykmeldingRepository
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

class ShadowActiveSykmeldingServiceTest :
    DescribeSpec({
        val dinesykmeldteService = io.mockk.mockk<DinesykmeldteService>()
        val repository = io.mockk.mockk<IActiveSykmeldingRepository>()
        val service = ShadowActiveSykmeldingService(dinesykmeldteService, repository)
        val logbackLogger = LoggerFactory.getLogger(ShadowActiveSykmeldingService::class.java) as Logger
        val logAppender = ListAppender<ILoggingEvent>()
        val originalLevel = logbackLogger.level
        val fnr = "12345678910"
        val orgnummer = "123456789"

        beforeSpec {
            logbackLogger.level = Level.WARN
            logAppender.start()
            logbackLogger.addAppender(logAppender)
        }

        afterSpec {
            logbackLogger.detachAppender(logAppender)
            logAppender.stop()
            logbackLogger.level = originalLevel
        }

        beforeTest {
            clearAllMocks()
            logAppender.list.clear()
        }

        fun warningMessages(): List<String> = logAppender.list.map { it.formattedMessage }
        fun warningEvents(): List<ILoggingEvent> = logAppender.list.toList()

        it("returns client result and logs no warning when shadow matches") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns true
            coEvery { repository.hasActiveSykmelding(fnr, orgnummer) } returns true

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe true
            warningMessages() shouldHaveSize 0
        }

        it("returns client result and logs warning when client=true and local=false") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns true
            coEvery { repository.hasActiveSykmelding(fnr, orgnummer) } returns false

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe true

            warningMessages() shouldHaveSize 1
            warningMessages().single() shouldBe
                "Shadow mismatch for active sykmelding for fnr=****8910 and orgnummer=123456789: client=true, local=false"
        }

        it("returns client result and logs warning when client=false and local=true") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns false
            coEvery { repository.hasActiveSykmelding(fnr, orgnummer) } returns true

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe false

            warningMessages() shouldHaveSize 1
            warningMessages().single() shouldBe
                "Shadow mismatch for active sykmelding for fnr=****8910 and orgnummer=123456789: client=false, local=true"
        }

        it("falls back to local query when client fails") {
            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(
                    fnr,
                    orgnummer
                )
            } throws RuntimeException("client failed")
            coEvery { repository.hasActiveSykmelding(fnr, orgnummer) } returns true

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe true

            warningMessages() shouldHaveSize 1
            warningMessages().single() shouldBe
                "Dinesykmeldte client failed for fnr=****8910 and orgnummer=123456789, using local fallback. Exception type=RuntimeException"
            warningEvents().single().throwableProxy shouldBe null
        }

        it("rethrows the original client exception when both calls fail") {
            val clientException = RuntimeException("client failed")
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } throws clientException
            coEvery { repository.hasActiveSykmelding(fnr, orgnummer) } throws RuntimeException("local failed")

            val exception = shouldThrow<RuntimeException> {
                service.getIsActiveSykmelding(fnr, orgnummer)
            }

            exception shouldBe clientException
            warningMessages() shouldHaveSize 2
        }

        it("uses client result when only local query fails") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns true
            coEvery { repository.hasActiveSykmelding(fnr, orgnummer) } throws RuntimeException("local failed")

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe true

            warningMessages() shouldHaveSize 1
            warningMessages().single() shouldBe
                "Local shadow query failed for fnr=****8910 and orgnummer=123456789, ignoring local result. Exception type=RuntimeException"
            warningEvents().single().throwableProxy shouldBe null
        }

        it("propagates cancellation exception from client call") {
            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(
                    fnr,
                    orgnummer
                )
            } throws CancellationException("cancelled")
            coEvery { repository.hasActiveSykmelding(fnr, orgnummer) } returns true

            shouldThrow<CancellationException> {
                service.getIsActiveSykmelding(fnr, orgnummer)
            }
        }

        it("propagates cancellation exception from local call") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns true
            coEvery { repository.hasActiveSykmelding(fnr, orgnummer) } throws CancellationException("cancelled")

            shouldThrow<CancellationException> {
                service.getIsActiveSykmelding(fnr, orgnummer)
            }
        }
    })
