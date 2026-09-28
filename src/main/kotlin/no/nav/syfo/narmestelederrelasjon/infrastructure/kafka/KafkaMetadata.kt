package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import java.time.OffsetDateTime

class KafkaMetadata(val timestamp: OffsetDateTime, val source: String)
