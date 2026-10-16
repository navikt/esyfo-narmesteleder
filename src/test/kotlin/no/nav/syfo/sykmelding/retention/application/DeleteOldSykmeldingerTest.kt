package no.nav.syfo.sykmelding.retention.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSingleton
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DeleteOldSykmeldingerTest :
    FunSpec({
        val clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC)
        val logOutput = ByteArrayOutputStream()
        val logger = LoggerFactory.getLogger(DeleteOldSykmeldinger::class.java) as Logger
        val originalLevel = logger.level
        val originalAdditive = logger.isAdditive
        val encoder = LogstashEncoder().apply {
            context = logger.loggerContext
            start()
        }
        val appender = OutputStreamAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            this.encoder = encoder
            setOutputStream(logOutput)
            start()
        }

        beforeSpec {
            logger.level = Level.INFO
            logger.isAdditive = false
            logger.addAppender(appender)
        }

        afterSpec {
            logger.detachAppender(appender)
            logger.level = originalLevel
            logger.isAdditive = originalAdditive
            appender.stop()
            encoder.stop()
        }

        beforeTest {
            logOutput.reset()
        }

        test("deletes batches until the repository has no old sykmeldinger") {
            val meterRegistry = SimpleMeterRegistry()
            val repository = RecordingSykmeldingRetentionRepository(2, 2, 0)

            DeleteOldSykmeldinger(repository, clock, SykmeldingRetentionMetrics(meterRegistry), batchSize = 2).execute()

            repository.requests shouldBe List(3) { LocalDate.parse("2025-03-01") to 2 }
            meterRegistry.deletedSykmeldinger() shouldBe 4.0
            assertCompletedCleanupLog(logOutput, deletedCount = 4)
        }

        test("counts and logs zero when no old sykmeldinger are deleted") {
            val meterRegistry = SimpleMeterRegistry()
            val repository = RecordingSykmeldingRetentionRepository(0)

            DeleteOldSykmeldinger(repository, clock, SykmeldingRetentionMetrics(meterRegistry)).execute()

            repository.requests.shouldBeSingleton()
            meterRegistry.deletedSykmeldinger() shouldBe 0.0
            assertCompletedCleanupLog(logOutput, deletedCount = 0)
        }
    })

private fun MeterRegistry.deletedSykmeldinger(): Double = counter(SykmeldingRetentionMetrics.DELETED_OLD_SYKMELDINGER).count()

private fun assertCompletedCleanupLog(logOutput: ByteArrayOutputStream, deletedCount: Int) {
    val logLines = logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
    logLines shouldHaveSize 1

    val logRecord = jacksonObjectMapper().readTree(logLines.single())
    logRecord["level"].asText() shouldBe "INFO"
    logRecord["logger_name"].asText() shouldBe DeleteOldSykmeldinger::class.java.name
    logRecord["message"].asText() shouldBe "Sykmelding retention cleanup completed"
    logRecord["event_type"].asText() shouldBe "sykmelding_retention_cleanup_completed"
    logRecord["deleted_count"].asInt() shouldBe deletedCount
}
