package no.nav.syfo.narmesteleder.api.v1

import kotlinx.serialization.Serializable
import java.util.UUID
import no.nav.syfo.narmesteleder.domain.LinemanagerUpdate
import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

@Serializable
data class Linemanager(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val manager: Manager,
) {
    fun toNlResponse(): NlResponse = NlResponse(
        orgnummer = orgnumber,
        leder = manager.toLeder(),
        sykmeldt = Sykmeldt(
            fnr = employeeIdentificationNumber,
            navn = "PLACEHOLDER"
        )
    )

    fun toNlbehovUpdate(id: UUID) = LinemanagerUpdate(
        employeeIdentificationNumber = employeeIdentificationNumber,
        orgnumber = orgnumber,
        leaderIdentificationNumber = manager.nationalIdentificationNumber,
        id = id
    )
}

@Serializable
data class Manager(
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
