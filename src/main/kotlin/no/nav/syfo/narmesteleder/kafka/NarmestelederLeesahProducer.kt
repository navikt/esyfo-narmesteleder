package no.nav.syfo.narmesteleder.kafka

import no.nav.syfo.util.logger
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

data class NarmestelederLeesahProducerRecord(
    val key: String,
    val value: String?,
)

class NarmestelederLeesahProducer(
    private val producer: KafkaProducer<String, String?>,
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
