package no.nav.syfo.sykmelding.infrastructure

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Proof that the employee confirmed a nærmeste leder in a sykmelding. `sykmelding_id` is the primary
 * key here, not a foreign key to `sykmelding`, because the proof has its own retention.
 * The sykmeldingsregister handler deletes both rows when it receives a tombstone.
 * The foreign key to `person` exists only in the migration, because `PersonTable` belongs to another capability.
 */
internal object NarmestelederConfirmationTable : Table("narmesteleder_confirmation") {
    val sykmeldingId = javaUUID("sykmelding_id")
    val personId = javaUUID("person_id")
    val orgnummer = varchar("orgnummer", 9)
    val confirmedAt = timestampWithTimeZone("confirmed_at")
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    val updated = timestampWithTimeZone("updated").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(sykmeldingId, name = "narmesteleder_confirmation_pkey")

    init {
        index("narmesteleder_confirmation_person_orgnummer_idx", false, personId, orgnummer)
    }
}
