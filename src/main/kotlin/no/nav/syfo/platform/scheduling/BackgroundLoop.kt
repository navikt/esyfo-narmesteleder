package no.nav.syfo.platform.scheduling

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import org.slf4j.event.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal val backgroundLoopIterationFailed = applicationEvent<String>(
    name = "background_loop_iteration_failed",
    level = Level.ERROR,
    message = "Background loop iteration failed; it will run again at the next interval",
    fields = mapOf("loop_name" to { it }),
)

/**
 * A per-pod loop without leader election. The caller owns the scope and supplies
 * the work; this mechanism knows nothing about claims or business rules.
 * [name] must be a static, bounded technical identifier, never derived from
 * personal data. It starts with a letter and contains letters, digits, "_" or "-".
 */
class BackgroundLoop(
    val name: String,
    private val interval: Duration,
    meterRegistry: MeterRegistry = METRICS_REGISTRY,
    private val iteration: suspend () -> Unit,
) {
    init {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) {
            "name must be a technical identifier of 1 to 64 characters"
        }
        require(interval.isPositive() && interval.isFinite()) { "interval must be positive and finite" }
    }

    private val logger = applicationLogger(javaClass)
    private val lifecycleLock = Any()
    private var job: Job? = null
    private val iterations = Counter.builder("${METRICS_NS}_background_loop_iterations_total")
        .tag("loop_name", name).register(meterRegistry)
    private val failures = Counter.builder("${METRICS_NS}_background_loop_failures_total")
        .tag("loop_name", name).register(meterRegistry)
    private val duration = Timer.builder("${METRICS_NS}_background_loop_iteration_duration")
        .tag("loop_name", name).register(meterRegistry)
    private val registry = meterRegistry

    fun start(scope: CoroutineScope) = synchronized(lifecycleLock) {
        // A cancelled but unfinished iteration must not overlap with a new loop.
        if (job?.isCompleted == false) return@synchronized
        job = scope.launch(CoroutineName(name)) {
            while (currentCoroutineContext().isActive) {
                iterations.increment()
                val sample = Timer.start(registry)
                try {
                    iteration()
                } catch (exception: Exception) {
                    // A timeout/cancellation owned by the iteration must not kill an active loop.
                    if (exception is CancellationException && !currentCoroutineContext().isActive) throw exception
                    failures.increment()
                    logger.logEvent(backgroundLoopIterationFailed, name, cause = exception)
                } finally {
                    sample.stop(duration)
                }
                delay(interval)
            }
        }
    }

    /** Cancels only this loop, waiting at most five seconds for cooperative work. */
    suspend fun stop() {
        val stoppingJob = synchronized(lifecycleLock) {
            job?.also { it.cancel() }
        } ?: return
        withContext(NonCancellable) {
            withTimeoutOrNull(5.seconds) { stoppingJob.join() }
        }
    }
}
