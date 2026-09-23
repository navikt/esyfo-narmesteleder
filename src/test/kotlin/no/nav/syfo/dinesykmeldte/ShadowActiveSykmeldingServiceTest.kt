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
import io.mockk.coVerify
import no.nav.syfo.sykmelding.exposed.IActiveSykmeldingRepository
import no.nav.syfo.sykmelding.exposed.LocalActiveSykmeldingResult
import org.slf4j.LoggerFactory
import java.util.UUID
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
        val orgnummer = "998877665"
        val maskedFnr = "****${fnr.takeLast(4)}"
        val localSykmeldingId = UUID.fromString("11111111-1111-1111-1111-111111111111")

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
            clearAllMocks(currentThreadOnly = true)
            logAppender.list.clear()
        }

        fun warningMessages(): List<String> = logAppender.list.map { it.formattedMessage }
        fun warningEvents(): List<ILoggingEvent> = logAppender.list.toList()
        fun warningArguments(): List<Any?> = logAppender.list.flatMap { it.keyValuePairs?.map { pair -> pair.value } ?: emptyList() }
        fun warningFields(): Map<String, Any?> = warningEvents().single().keyValuePairs.associate { it.key to it.value }
        fun localResult(isActive: Boolean, sykmeldingId: UUID? = null): LocalActiveSykmeldingResult = LocalActiveSykmeldingResult(isActive = isActive, sykmeldingId = sykmeldingId)
        fun assertSensitiveValueNotLogged(value: String) {
            warningMessages().any { it.contains(value) } shouldBe false
            warningArguments().map { it?.toString().orEmpty() }.any { it.contains(value) } shouldBe false
        }
        fun assertNoSensitiveValuesLogged() {
            listOf(fnr, orgnummer, maskedFnr, localSykmeldingId.toString()).forEach(::assertSensitiveValueNotLogged)
        }

        it("returns client result and logs no warning when shadow matches") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns true
            coEvery { repository.findActiveSykmelding(fnr, orgnummer) } returns localResult(isActive = true, sykmeldingId = localSykmeldingId)

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe true
            warningMessages() shouldHaveSize 0
        }

        it("returns client result and logs warning when client=true and local=false") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns true
            coEvery { repository.findActiveSykmelding(fnr, orgnummer) } returns localResult(isActive = false)

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe true

            warningMessages() shouldHaveSize 1
            warningMessages().single() shouldBe "Active sick leave shadow comparison is degraded"
            warningFields()["reason"] shouldBe "MISMATCH"
            warningFields()["remote_result"] shouldBe true
            warningFields()["local_result"] shouldBe false
            warningEvents().single().throwableProxy shouldBe null
            assertNoSensitiveValuesLogged()
        }

        it("returns client result and logs warning when client=false and local=true") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns false
            coEvery { repository.findActiveSykmelding(fnr, orgnummer) } returns localResult(isActive = true, sykmeldingId = localSykmeldingId)

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe false

            warningMessages() shouldHaveSize 1
            warningMessages().single() shouldBe "Active sick leave shadow comparison is degraded"
            warningFields()["reason"] shouldBe "MISMATCH"
            warningFields()["remote_result"] shouldBe false
            warningFields()["local_result"] shouldBe true
            warningEvents().single().throwableProxy shouldBe null
            assertNoSensitiveValuesLogged()
        }

        it("rethrows the original client exception when client fails even if local succeeds") {
            val clientException = RuntimeException("client failed")
            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(
                    fnr,
                    orgnummer
                )
            } throws clientException
            coEvery { repository.findActiveSykmelding(fnr, orgnummer) } returns localResult(isActive = true, sykmeldingId = localSykmeldingId)

            val exception = shouldThrow<RuntimeException> {
                service.getIsActiveSykmelding(fnr, orgnummer)
            }

            exception shouldBe clientException
            coVerify(exactly = 0) { repository.findActiveSykmelding(any(), any()) }
            warningMessages() shouldHaveSize 0
            assertNoSensitiveValuesLogged()
        }

        it("rethrows the original client exception when both calls fail") {
            val clientException = RuntimeException("client failed")
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } throws clientException
            coEvery { repository.findActiveSykmelding(fnr, orgnummer) } throws RuntimeException("local failed")

            val exception = shouldThrow<RuntimeException> {
                service.getIsActiveSykmelding(fnr, orgnummer)
            }

            exception shouldBe clientException
            warningMessages() shouldHaveSize 0
            assertNoSensitiveValuesLogged()
        }

        it("uses client result when only local query fails") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns true
            coEvery { repository.findActiveSykmelding(fnr, orgnummer) } throws RuntimeException("local failed")

            service.getIsActiveSykmelding(fnr, orgnummer) shouldBe true

            warningMessages() shouldHaveSize 1
            warningMessages().single() shouldBe "Active sick leave shadow comparison is degraded"
            warningFields()["event_type"] shouldBe "sick_leave_shadow_degraded"
            warningFields()["reason"] shouldBe "QUERY_FAILED"
            warningFields()["cause_type"] shouldBe "RuntimeException"
            warningFields().containsKey("outcome") shouldBe false
            warningEvents().single().throwableProxy.message shouldBe "java.lang.RuntimeException"
            assertNoSensitiveValuesLogged()
        }

        it("propagates cancellation exception from client call") {
            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(
                    fnr,
                    orgnummer
                )
            } throws CancellationException("cancelled")
            coEvery { repository.findActiveSykmelding(fnr, orgnummer) } returns localResult(isActive = true, sykmeldingId = localSykmeldingId)

            shouldThrow<CancellationException> {
                service.getIsActiveSykmelding(fnr, orgnummer)
            }
        }

        it("propagates cancellation exception from local call") {
            coEvery { dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer) } returns true
            coEvery { repository.findActiveSykmelding(fnr, orgnummer) } throws CancellationException("cancelled")

            shouldThrow<CancellationException> {
                service.getIsActiveSykmelding(fnr, orgnummer)
            }
        }
    })
