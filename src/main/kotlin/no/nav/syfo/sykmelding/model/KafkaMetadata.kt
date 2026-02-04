package no.nav.syfo.sykmelding.model

import java.time.OffsetDateTime

data class KafkaMetadata(
    val sykmeldingId: String,
    val timestamp: OffsetDateTime,
    val fnr: String,
    val source: String
)
