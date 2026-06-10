package no.nav.syfo.outbox

import no.nav.syfo.application.task.ScheduledLeaderTask
import kotlin.time.Duration

class OutboxDispatchTask(
    private val outboxDirectSender: OutboxDirectSender,
    interval: Duration,
) : ScheduledLeaderTask(
    name = OutboxDispatchTask::class.qualifiedName!!,
    interval = interval,
) {
    override suspend fun execute() {
        outboxDirectSender.processPendingBatch()
    }
}
