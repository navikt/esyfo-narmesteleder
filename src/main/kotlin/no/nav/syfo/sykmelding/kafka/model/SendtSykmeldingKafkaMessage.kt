package no.nav.syfo.sykmelding.kafka.model

data class SendtSykmeldingKafkaMessage(
    val sykmelding: ArbeidsgiverSykmelding,
    val kafkaMetadata: KafkaMetadata,
    val event: Event,
)
