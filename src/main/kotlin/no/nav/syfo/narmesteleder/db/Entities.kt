package no.nav.syfo.narmesteleder.db

import java.sql.ResultSet
import java.util.*
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite

data class NarmestelederBehovEntity(
    val id: UUID? = null,
    val orgnummer: String,
    val hovedenhetOrgnummer: String,
    val sykmeldtFnr: String,
    val narmestelederFnr: String,
    val leesahStatus: String,
    val behovStatus: BehovStatus = BehovStatus.RECEIVED,
    val dialogId: UUID? = null,
    val narmesteLederId: UUID? = null,
) {
    companion object {
        fun fromLinemanagerRequirementWrite(
            linemanagerRequirementWrite: LinemanagerRequirementWrite,
            hovedenhetOrgnummer: String,
            behovStatus: BehovStatus
        ): NarmestelederBehovEntity {
            with(linemanagerRequirementWrite) {
                return NarmestelederBehovEntity(
                    orgnummer = orgnumber,
                    hovedenhetOrgnummer = hovedenhetOrgnummer,
                    sykmeldtFnr = employeeIdentificationNumber,
                    narmestelederFnr = managerIdentificationNumber,
                    leesahStatus = leesahStatus,
                    narmesteLederId = linemanagerId,
                    behovStatus = behovStatus,
                )
            }
        }
    }
}

fun ResultSet.toNarmestelederBehovEntity(): NarmestelederBehovEntity =
    NarmestelederBehovEntity(
        id = this.getObject("id", UUID::class.java),
        orgnummer = this.getString("orgnummer"),
        hovedenhetOrgnummer = this.getString("hovedenhet_orgnummer"),
        sykmeldtFnr = this.getString("sykemeldt_fnr"),
        narmestelederFnr = this.getString("narmeste_leder_fnr"),
        leesahStatus = this.getString("leesah_status"),
        behovStatus = BehovStatus.valueOf(this.getString("behov_status")),
        dialogId = this.getObject("dialog_id") as? UUID,
        narmesteLederId = this.getObject("narmesteleder_id", UUID::class.java),
    )
