package no.nav.syfo.narmesteleder.db

import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import java.sql.ResultSet
import java.time.Instant
import java.util.*

data class NarmestelederBehovEntity(
    val id: UUID? = null,
    val orgnummer: String,
    val hovedenhetOrgnummer: String,
    val sykmeldtFnr: String,
    val narmestelederFnr: String? = null,
    val behovReason: BehovReason,
    val behovStatus: BehovStatus = BehovStatus.BEHOV_CREATED,
    val dialogId: UUID? = null,
    val avbruttNarmesteLederId: UUID? = null,
    val fornavn: String? = null,
    val mellomnavn: String? = null,
    val etternavn: String? = null,
    val created: Instant = Instant.now(),
    val updated: Instant = Instant.now(),
    val dialogDeletePerformed: Instant? = null,
) {
    companion object {
        fun fromLinemanagerRequirementWrite(
            linemanagerRequirementWrite: LinemanagerRequirementWrite,
            hovedenhetOrgnummer: String,
            behovStatus: BehovStatus
        ): NarmestelederBehovEntity {
            with(linemanagerRequirementWrite) {
                return NarmestelederBehovEntity(
                    orgnummer = orgNumber,
                    hovedenhetOrgnummer = hovedenhetOrgnummer,
                    sykmeldtFnr = employeeIdentificationNumber,
                    narmestelederFnr = managerIdentificationNumber,
                    behovReason = behovReason,
                    avbruttNarmesteLederId = revokedLinemanagerId,
                    behovStatus = behovStatus,
                )
            }
        }
    }
}

fun ResultSet.toNarmestelederBehovEntity(): NarmestelederBehovEntity = NarmestelederBehovEntity(
    id = this.getObject("id", UUID::class.java),
    orgnummer = this.getString("orgnummer"),
    hovedenhetOrgnummer = this.getString("hovedenhet_orgnummer"),
    sykmeldtFnr = this.getString("sykemeldt_fnr"),
    narmestelederFnr = this.getString("narmeste_leder_fnr"),
    behovReason = BehovReason.valueOf(this.getString("behov_reason")),
    behovStatus = BehovStatus.valueOf(this.getString("behov_status")),
    dialogId = this.getObject("dialog_id") as? UUID,
    avbruttNarmesteLederId = this.getObject("avbrutt_narmesteleder_id", UUID::class.java),
    fornavn = this.getString("fornavn"),
    mellomnavn = this.getString("mellomnavn"),
    etternavn = this.getString("etternavn"),
    created = getTimestamp("created").toInstant(),
    updated = getTimestamp("updated").toInstant(),
    dialogDeletePerformed = getTimestamp("dialog_delete_performed")?.toInstant()
)
