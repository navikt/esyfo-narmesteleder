package no.nav.syfo.sykmelding.application

import java.time.OffsetDateTime

data class KafkaMetadata(
    val sykmeldingId: String,
    val timestamp: OffsetDateTime,
    val fnr: String,
    val source: String
)
