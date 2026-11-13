package no.nav.syfo.sykmelding.infrastructure.kafka

import no.nav.syfo.platform.kafka.BatchMessageHandler
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.observability.logger
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetter
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetterRepository
import no.nav.syfo.sykmelding.application.SykmeldingsregisterMessage
import no.nav.syfo.sykmelding.application.SyncSykmeldingsregister
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.event.Level

private val recordDeadLettered = applicationEvent<SykmeldingsregisterDeadLetter>(
    name = "sykmeldingsregister_record_dead_lettered",
    level = Level.WARN,
    message = "Sykmeldingsregister record could not be parsed and was stored as a dead letter",
    fields = mapOf(
        "partition" to { it.position.partition },
        "offset" to { it.position.offset },
        "sykmelding_id" to { it.sykmeldingId?.toString() },
        "reason" to { it.reason },
    ),
)

private data class ParsedBatch(
    val messages: List<SykmeldingsregisterMessage>,
    val deadLetters: List<SykmeldingsregisterDeadLetter>,
)

/**
 * Handles a batch from the sykmeldingsregister topic. Records that can never be parsed are stored as dead
 * letters before the valid messages are synced, so a later tombstone in the same batch can remove them.
 * Only dead letters stored for the first time are reported, so a redelivered batch does not report them again.
 * A failure while storing or syncing propagates, so the batch is not committed and is redelivered.
 */
class SykmeldingsregisterBatchHandler(
    private val syncSykmeldingsregister: SyncSykmeldingsregister,
    private val deadLetterRepository: SykmeldingsregisterDeadLetterRepository,
    private val parser: SykmeldingsregisterRecordParser,
    private val metrics: SykmeldingsregisterMetrics,
) : BatchMessageHandler<String, String?> {
    private val logger = logger()

    override suspend fun handleBatch(records: List<ConsumerRecord<String, String?>>) {
        val batch = parseBatch(records)

        storeDeadLetters(batch.deadLetters)
        sync(batch.messages)
    }

    private suspend fun storeDeadLetters(deadLetters: List<SykmeldingsregisterDeadLetter>) {
        deadLetterRepository.saveAll(deadLetters).forEach {
            logger.logEvent(recordDeadLettered, it)
            metrics.countDeadLetter(it.rejection)
        }
    }

    private suspend fun sync(messages: List<SykmeldingsregisterMessage>) {
        if (messages.isEmpty()) return
        syncSykmeldingsregister.execute(messages)
        metrics.countTombstones(messages.count { it is SykmeldingsregisterMessage.Tombstone })
    }

    private fun parseBatch(records: List<ConsumerRecord<String, String?>>): ParsedBatch {
        val parsed = records.map { parser.parse(it) }
        return ParsedBatch(
            messages = parsed.filterIsInstance<ParsedSykmeldingsregisterRecord.Accepted>().map { it.message },
            deadLetters = parsed.filterIsInstance<ParsedSykmeldingsregisterRecord.Rejected>().map { it.deadLetter },
        )
    }
}
