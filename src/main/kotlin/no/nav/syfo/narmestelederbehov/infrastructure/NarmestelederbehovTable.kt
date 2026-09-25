package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmesteleder.domain.BehovStatus
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.postgresql.util.PGobject

object NarmestelederbehovTable : Table("nl_behov") {
    val id = javaUUID("id")
    val orgnummer = varchar("orgnummer", 9)
    val sykmeldtFnr = varchar("sykemeldt_fnr", 11)
    val behovStatus = registerColumn("behov_status", BehovStatusColumnType())
    val dialogId = javaUUID("dialog_id").nullable()
}

private class BehovStatusColumnType : ColumnType<BehovStatus>() {
    override fun sqlType(): String = "BEHOV_STATUS"

    override fun valueFromDB(value: Any): BehovStatus = BehovStatus.valueOf(value.toString())

    override fun notNullValueToDB(value: BehovStatus): Any = PGobject().apply {
        type = sqlType()
        this.value = value.name
    }

    override fun nonNullValueToString(value: BehovStatus): String = "'${value.name}'"
}
