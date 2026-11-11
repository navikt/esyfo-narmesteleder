package no.nav.syfo.sykmelding.infrastructure

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Sent sykmeldinger from sykmeldingsregister (V34), one row per sykmelding.
 * The index on `(person_id, orgnummer, max_tom)` serves the active-sykmelding lookup and the foreign key.
 * The foreign key to `person` exists only in the migration, because `PersonTable` belongs to another capability.
 */
internal object SykmeldingTable : Table("sykmelding") {
    val sykmeldingId = javaUUID("sykmelding_id")
    val personId = javaUUID("person_id")
    val fnr = varchar("fnr", 11)
    val orgnummer = varchar("orgnummer", 9)
    val sentAt = timestampWithTimeZone("sent_at")
    val maxTom = date("max_tom")
    val sourcePartition = integer("source_partition")
    val sourceOffset = long("source_offset")
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    val updated = timestampWithTimeZone("updated").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(sykmeldingId, name = "sykmelding_pkey")

    init {
        index("sykmelding_person_orgnummer_max_tom_idx", false, personId, orgnummer, maxTom)
    }
}
