package no.nav.syfo.narmesteleder.kafka.model

interface NarmestelederResponseKafkaMessage
data class NarmestelederRelationResponseKafkaMessage(
    val kafkaMetadata: KafkaMetadata,
    val nlResponse: NlResponse
) : NarmestelederResponseKafkaMessage

data class NarmestelederAvbruddResponseKafkaMessage(
    val kafkaMetadata: KafkaMetadata,
    val nlAvbrutt: NlAvbrutt,
) : NarmestelederResponseKafkaMessage
