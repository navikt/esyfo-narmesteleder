package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import no.nav.syfo.faker
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal fun narmestelederLeesahKafkaMessage() = NarmestelederLeesahKafkaMessage(
    narmesteLederId = UUID.randomUUID(),
    fnr = faker.fnr(),
    orgnummer = faker.orgnummer(),
    narmesteLederFnr = faker.fnr(),
    narmesteLederTelefonnummer = faker.mobilnummer(),
    narmesteLederEpost = faker.email(),
    aktivFom = LocalDate.now(),
    aktivTom = null,
    arbeidsgiverForskutterer = true,
    timestamp = OffsetDateTime.now(),
    status = LeesahStatus.NY_LEDER,
)
