package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.platform.database.PostgresGenRandomUuid
import no.nav.syfo.platform.database.PostgresNow
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

object NarmestelederbehovTable : Table("nl_behov") {
    val id = javaUUID("id").defaultExpression(PostgresGenRandomUuid)
    val orgnummer = varchar("orgnummer", 9)
    val hovedenhetOrgnummer = varchar("hovedenhet_orgnummer", 9).nullable()
    val sykmeldtFnr = varchar("sykemeldt_fnr", 11)
    val narmestelederFnr = varchar("narmeste_leder_fnr", 11).nullable()
    val behovReason = varchar("behov_reason", 255).nullable()
    val behovStatus = registerColumn("behov_status", BehovStatusColumnType()).default(BehovStatus.BEHOV_CREATED)
    val avbruttNarmestelederId = javaUUID("avbrutt_narmesteleder_id").nullable()
    val fornavn = varchar("fornavn", 100).nullable()
    val mellomnavn = varchar("mellomnavn", 100).nullable()
    val etternavn = varchar("etternavn", 100).nullable()
    val dialogId = javaUUID("dialog_id").nullable()
    val dialogDeletePerformed = timestampWithTimeZone("dialog_delete_performed").nullable()
    val expiredInDialogporten = timestampWithTimeZone("expired_in_dialogporten").nullable()
    val created = timestampWithTimeZone("created").defaultExpression(PostgresNow)
    val updated = timestampWithTimeZone("updated").defaultExpression(PostgresNow)

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_nl_behov__orgnummer_sykemeldt_fnr_behov_status", false, orgnummer, sykmeldtFnr, behovStatus)
        index("idx_nl_behov__orgnummer_behov_status_created", false, orgnummer, behovStatus, created)
        index("idx_nl_behov__behov_status_created", false, behovStatus, created)
    }
}

private class BehovStatusColumnType : ColumnType<BehovStatus>() {
    override fun sqlType(): String = "BEHOV_STATUS"

    override fun valueFromDB(value: Any): BehovStatus = BehovStatus.valueOf(value.toString())

    override fun notNullValueToDB(value: BehovStatus): Any = PGobject().apply {
        type = sqlType()
        this.value = value.name
    }

    override fun nonNullValueToString(value: BehovStatus): String = "'${value.name}'::${sqlType().lowercase()}"
}
