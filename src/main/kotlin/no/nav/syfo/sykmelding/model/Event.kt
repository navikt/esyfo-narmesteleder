package no.nav.syfo.sykmelding.model

import java.time.OffsetDateTime

data class Event(
    val sykmeldingId: String,
    val timestamp: OffsetDateTime,
    val arbeidsgiver: Arbeidsgiver? = null,
    val brukerSvar: BrukerSvar? = null,
)

data class BrukerSvar(
    val riktigNarmesteLeder: RiktigNarmesteLeder?,
)

data class RiktigNarmesteLeder(
    val sporsmaltekst: String,
    val svar: String
)

data class Arbeidsgiver(
    val orgnummer: String,
    val juridiskOrgnummer: String? = null,
    val orgNavn: String
)
