package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

object NarmestelederbehovTable : Table("nl_behov") {
    val id = javaUUID("id")
    val orgnummer = varchar("orgnummer", 9)
    val sykmeldtFnr = varchar("sykemeldt_fnr", 11)
    val hovedenhetOrgnummer = varchar("hovedenhet_orgnummer", 9).nullable()
    val narmestelederFnr = varchar("narmeste_leder_fnr", 11).nullable()
    val fornavn = varchar("fornavn", 100).nullable()
    val mellomnavn = varchar("mellomnavn", 100).nullable()
    val etternavn = varchar("etternavn", 100).nullable()
    val created = timestampWithTimeZone("created")
    val updated = timestampWithTimeZone("updated")
    val behovReason = varchar("behov_reason", 255)
    val behovStatus = registerColumn("behov_status", BehovStatusColumnType())
    val dialogId = javaUUID("dialog_id").nullable()
    val avbruttNarmestelederId = javaUUID("avbrutt_narmesteleder_id").nullable()
}

internal val openNarmestelederbehovStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)

internal fun ResultRow.toNarmestelederbehovDetails() = NarmestelederbehovDetails(
    id = NarmestelederbehovId(this[NarmestelederbehovTable.id]),
    employeeIdent = PersonIdent(this[NarmestelederbehovTable.sykmeldtFnr]),
    organizationNumber = OrganizationNumber(this[NarmestelederbehovTable.orgnummer]),
    mainOrganizationNumber = requireNotNull(this[NarmestelederbehovTable.hovedenhetOrgnummer]),
    managerIdent = this[NarmestelederbehovTable.narmestelederFnr]?.let(::PersonIdent),
    firstName = this[NarmestelederbehovTable.fornavn],
    middleName = this[NarmestelederbehovTable.mellomnavn],
    lastName = this[NarmestelederbehovTable.etternavn],
    created = this[NarmestelederbehovTable.created].toInstant(),
    updated = this[NarmestelederbehovTable.updated].toInstant(),
    status = this[NarmestelederbehovTable.behovStatus],
    reason = BehovReason.valueOf(this[NarmestelederbehovTable.behovReason]),
)

private class BehovStatusColumnType : ColumnType<BehovStatus>() {
    override fun sqlType(): String = "BEHOV_STATUS"

    override fun valueFromDB(value: Any): BehovStatus = BehovStatus.valueOf(value.toString())

    override fun notNullValueToDB(value: BehovStatus): Any = PGobject().apply {
        type = sqlType()
        this.value = value.name
    }

    override fun nonNullValueToString(value: BehovStatus): String = "'${value.name}'"
}
