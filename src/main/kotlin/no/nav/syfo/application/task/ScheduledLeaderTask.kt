package no.nav.syfo.application.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.util.logger
import org.slf4j.event.Level
import kotlin.time.Duration

private data class ScheduledTaskFailedDetails(
    val taskName: String,
)

private val scheduledTaskFailed = applicationEvent<ScheduledTaskFailedDetails>(
    name = "scheduled_task_failed",
    level = Level.ERROR,
    message = "Scheduled task failed; it will run again at the next interval",
    fields = mapOf(
        "task_name" to { it.taskName },
    ),
)

abstract class ScheduledLeaderTask(
    name: String,
    private val interval: Duration,
) {
    private val logger = logger(name)
    private val taskName = name

    abstract suspend fun execute()

    suspend fun runTask() = coroutineScope {
        try {
            while (isActive) {
                try {
                    execute()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    logger.logEvent(scheduledTaskFailed, ScheduledTaskFailedDetails(taskName = taskName), cause = ex)
                }
                delay(interval)
            }
        } catch (_: CancellationException) {
            logger.info("$taskName stopped gracefully")
        }
    }
}
