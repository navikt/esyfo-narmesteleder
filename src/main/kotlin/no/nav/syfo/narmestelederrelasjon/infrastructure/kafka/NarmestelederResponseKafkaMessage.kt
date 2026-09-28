package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

interface NarmestelederResponseKafkaMessage
data class NarmestelederRelationResponseKafkaMessage(
    val kafkaMetadata: KafkaMetadata,
    val nlResponse: NlResponse
) : NarmestelederResponseKafkaMessage

data class NarmestelederAvbruddResponseKafkaMessage(
    val kafkaMetadata: KafkaMetadata,
    val nlAvbrutt: NlAvbrutt,
) : NarmestelederResponseKafkaMessage
