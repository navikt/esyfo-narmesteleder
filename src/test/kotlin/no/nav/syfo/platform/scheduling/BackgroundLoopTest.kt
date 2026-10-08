package no.nav.syfo.platform.scheduling

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.application.metric.METRICS_NS
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundLoopTest :
    FunSpec({
        val logger = LoggerFactory.getLogger(BackgroundLoop::class.java) as Logger
        val originalSettings = logger.level to logger.isAdditive
        val productionLogging = LoggerContext()
        lateinit var productionAppender: Appender<ILoggingEvent>
        lateinit var capture: LogCapture
        val contract = RuntimeLogContract.forEvents(
            backgroundLoopIterationFailed,
            exceptionTypes = setOf("IllegalStateException", "CancellationException", "TimeoutCancellationException"),
        )

        beforeSpec {
            productionLogging.putProperty("NAIS_CLUSTER_NAME", "test")
            JoranConfigurator().apply {
                context = productionLogging
                doConfigure("src/main/resources/logback.xml")
            }
            productionAppender = requireNotNull(productionLogging.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("stdout_json"))
            logger.level = Level.TRACE
            logger.isAdditive = false
            logger.addAppender(productionAppender)
        }
        afterSpec {
            logger.detachAppender(productionAppender)
            logger.level = originalSettings.first
            logger.isAdditive = originalSettings.second
            productionLogging.stop()
        }
        beforeTest { capture = captureLogs(logger, "stdout_json") }
        afterTest {
            try {
                capture.records.forEach {
                    contract.assertValid(listOf(it))
                    it shouldNotContain "private-payload-canary"
                    it shouldNotContain "private-cause-canary"
                }
            } finally {
                capture.close()
            }
        }

        test("runs immediately and repeatedly after the interval; start and stop are idempotent") {
            runTest {
                val registry = SimpleMeterRegistry()
                var iterations = 0
                val loop = BackgroundLoop("test-loop", 100.milliseconds, registry) { iterations++ }

                loop.start(this)
                loop.start(this)
                runCurrent()
                iterations shouldBe 1
                advanceTimeBy(99)
                runCurrent()
                iterations shouldBe 1
                advanceTimeBy(1)
                runCurrent()
                iterations shouldBe 2

                loop.stop()
                loop.stop()
                advanceTimeBy(1_000)
                runCurrent()
                iterations shouldBe 2
                registry.get("${METRICS_NS}_background_loop_iterations_total").counter().count() shouldBe 2.0
                registry.get("${METRICS_NS}_background_loop_iteration_duration").timer().count() shouldBe 2L
            }
        }

        test("isolates failures, emits sanitized structured logs and counts failed iterations") {
            runTest {
                val registry = SimpleMeterRegistry()
                var iterations = 0
                val loop = BackgroundLoop("failing-loop", 100.milliseconds, registry) {
                    iterations++
                    if (iterations == 1) {
                        throw IllegalStateException("private-payload-canary", RuntimeException("private-cause-canary"))
                    }
                }
                loop.start(this)
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                loop.stop()

                iterations shouldBe 2
                registry.get("${METRICS_NS}_background_loop_failures_total")
                    .tag("loop_name", "failing-loop").counter().count() shouldBe 1.0
                registry.get("${METRICS_NS}_background_loop_iterations_total").counter().count() shouldBe 2.0
                registry.get("${METRICS_NS}_background_loop_iteration_duration").timer().count() shouldBe 2L
                contract.assertValid(capture.records, expectedCount = 1)
                val record = jacksonObjectMapper().readTree(capture.records.single())
                record["event_type"].asText() shouldBe "background_loop_iteration_failed"
                record["level"].asText() shouldBe "ERROR"
                record["loop_name"].asText() shouldBe "failing-loop"
                record["exception_type"].asText() shouldBe "IllegalStateException"
            }
        }

        test("stop cancels an in-flight iteration without cancelling the caller scope") {
            runTest {
                val registry = SimpleMeterRegistry()
                var finished = false
                val loop = BackgroundLoop("waiting-loop", 100.milliseconds, registry) {
                    try {
                        awaitCancellation()
                    } finally {
                        finished = true
                    }
                }
                loop.stop()
                loop.start(this)
                runCurrent()
                loop.stop()
                finished shouldBe true
                delay(100)
                registry.get("${METRICS_NS}_background_loop_iterations_total").counter().count() shouldBe 1.0
                registry.get("${METRICS_NS}_background_loop_failures_total").counter().count() shouldBe 0.0
                capture.records.size shouldBe 0
            }
        }

        test("an inner timeout is counted and logged, the loop repeats, and stop cancels the next timeout cleanly") {
            runTest {
                val registry = SimpleMeterRegistry()
                var iterations = 0
                val loop = BackgroundLoop("timeout-loop", 100.milliseconds, registry) {
                    iterations++
                    withTimeout(50.milliseconds) { awaitCancellation() }
                }
                loop.start(this)
                runCurrent()
                advanceTimeBy(50)
                runCurrent()
                registry.get("${METRICS_NS}_background_loop_failures_total").counter().count() shouldBe 1.0

                advanceTimeBy(100)
                runCurrent()
                iterations shouldBe 2
                loop.stop()
                advanceTimeBy(1_000)
                runCurrent()

                iterations shouldBe 2
                registry.get("${METRICS_NS}_background_loop_failures_total").counter().count() shouldBe 1.0
                registry.get("${METRICS_NS}_background_loop_iterations_total").counter().count() shouldBe 2.0
                registry.get("${METRICS_NS}_background_loop_iteration_duration").timer().count() shouldBe 2L
                contract.assertValid(capture.records, expectedCount = 1)
                val record = jacksonObjectMapper().readTree(capture.records.single())
                record["event_type"].asText() shouldBe "background_loop_iteration_failed"
                record["exception_type"].asText() shouldBe "TimeoutCancellationException"
                record["loop_name"].asText() shouldBe "timeout-loop"
            }
        }
    })
