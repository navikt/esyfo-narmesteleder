package no.nav.syfo.sykmelding.kafka.model

import java.time.OffsetDateTime

data class KafkaMetadata(
    val sykmeldingId: String,
    val timestamp: OffsetDateTime,
    val fnr: String,
    val source: String
)
