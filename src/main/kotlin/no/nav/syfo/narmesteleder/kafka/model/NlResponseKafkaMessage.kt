package no.nav.syfo.narmesteleder.kafka.model

interface INlResponseKafkaMessage
data class NlRelationResponseKafkaMessage(
    val kafkaMetadata: KafkaMetadata,
    val nlResponse: NlResponse
) : INlResponseKafkaMessage

data class NlAvbruddResponseKafkaMessage(
    val kafkaMetadata: KafkaMetadata,
    val nlAvbrutt: NlAvbrutt,
) : INlResponseKafkaMessage
