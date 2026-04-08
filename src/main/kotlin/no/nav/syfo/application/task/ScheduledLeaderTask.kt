package no.nav.syfo.application.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import no.nav.syfo.util.logger
import kotlin.time.Duration

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
                    logger.error("Error while executing $taskName", ex)
                }
                delay(interval)
            }
        } catch (ex: CancellationException) {
            logger.info("Cancelled $taskName", ex)
        }
    }
}
