package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import no.nav.syfo.platform.observability.logger
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord

data class NarmestelederLeesahProducerRecord(
    val key: String,
    val value: String?,
)

class NarmestelederLeesahProducer(
    private val producer: Producer<String, String?>,
) {
    fun sendLeesahBatch(records: List<NarmestelederLeesahProducerRecord>) {
        if (records.isEmpty()) {
            return
        }

        records.forEach { record ->
            producer.send(
                ProducerRecord(
                    NARMESTELEDER_LEESAH_TOPIC,
                    record.key,
                    record.value,
                )
            ).get()
        }

        logger.info(
            "Published {} leesah records to {}",
            records.size,
            NARMESTELEDER_LEESAH_TOPIC,
        )
    }

    companion object {
        const val NARMESTELEDER_LEESAH_TOPIC = "team-esyfo.syfo-narmesteleder-leesah"
        private val logger = logger()
    }
}
