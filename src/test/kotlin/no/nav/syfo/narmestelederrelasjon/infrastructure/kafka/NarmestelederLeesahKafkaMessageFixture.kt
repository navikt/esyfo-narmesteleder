package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import faker
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal fun narmestelederLeesahKafkaMessage() = NarmestelederLeesahKafkaMessage(
    narmesteLederId = UUID.randomUUID(),
    fnr = faker.numerify("###########"),
    orgnummer = faker.numerify("#########"),
    narmesteLederFnr = faker.numerify("###########"),
    narmesteLederTelefonnummer = faker.phoneNumber().cellPhone(),
    narmesteLederEpost = faker.internet().emailAddress(),
    aktivFom = LocalDate.now(),
    aktivTom = null,
    arbeidsgiverForskutterer = true,
    timestamp = OffsetDateTime.now(),
    status = LeesahStatus.NY_LEDER,
)
