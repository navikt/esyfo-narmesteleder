package no.nav.syfo.narmesteleder.domain

import java.util.UUID

data class NlBehovWrite(
    val sykmeldtFnr: String,
    val orgnummer: String,
    val narmesteLederFnr: String,
    val leesahStatus: String,
)

data class NlBehovUpdate(
    val id: UUID,
    val sykmeldtFnr: String,
    val orgnummer: String,
    val narmesteLederFnr: String,
)

data class NlBehovRead(
    val id: UUID,
    val sykmeldtFnr: String,
    val orgnummer: String,
    val hovedenhetOrgnummer: String,
    val narmesteLederFnr: String,
    val name: Name
)

data class Name(
    val firstName: String,
    val lastName: String,
    val middleName: String?,
)
