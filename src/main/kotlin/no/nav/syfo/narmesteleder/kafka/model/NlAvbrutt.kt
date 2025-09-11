package no.nav.syfo.narmesteleder.kafka.model

import java.time.OffsetDateTime
import java.time.ZoneOffset

data class NlAvbrutt(
    val orgnummer: String,
    val sykmeldtFnr: String,
    val aktivTom: OffsetDateTime =  OffsetDateTime.now(ZoneOffset.UTC),
)
