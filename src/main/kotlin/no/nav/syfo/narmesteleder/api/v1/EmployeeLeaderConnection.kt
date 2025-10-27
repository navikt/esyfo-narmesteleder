package no.nav.syfo.narmesteleder.api.v1

import java.util.UUID
import no.nav.syfo.narmesteleder.domain.NlBehovUpdate
import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

data class EmployeeLeaderConnection(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val leader: Leader,
) {
    fun toNlResponse(): NlResponse = NlResponse(
        orgnummer = orgnumber,
        leder = leader.toLeder(),
        sykmeldt = Sykmeldt(
            fnr = employeeIdentificationNumber,
            navn = "PLACEHOLDER"
        )
    )

    fun toNlbehovUpdate(id: UUID) = NlBehovUpdate(
        sykmeldtFnr = employeeIdentificationNumber,
        orgnummer = orgnumber,
        narmesteLederFnr = leader.nationalIdentificationNumber,
        id = id
    )
}

data class Leader(
    val nationalIdentificationNumber: String,
    val mobile: String,
    val email: String,
    val firstName: String,
    val lastName: String,
) {
    fun toLeder() = Leder(
        fnr = nationalIdentificationNumber,
        mobil = mobile,
        epost = email,
        fornavn = firstName,
        etternavn = lastName,
    )
}
