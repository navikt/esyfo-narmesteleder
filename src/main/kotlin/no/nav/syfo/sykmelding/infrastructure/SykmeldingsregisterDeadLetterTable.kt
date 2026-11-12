package no.nav.syfo.sykmelding.infrastructure

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Sykmeldingsregister records that could not be stored, keyed by Kafka position so a
 * redelivered record is written once. Stores no payload and no personal identifiers.
 *
 * `sykmelding_id` is the record key. It shows whether a newer record for the same sykmelding
 * has superseded the dead letter, and lets a tombstone delete it. It is null when the key is
 * missing or not a valid UUID; a raw invalid key is never stored.
 *
 * `reason` names the failure category and the failing field (for example its path), never field
 * values. It has no CHECK constraint: a constraint violation would be retried instead of
 * dead-lettered and could stop the consumer.
 */
internal object SykmeldingsregisterDeadLetterTable : Table("sykmeldingsregister_dead_letter") {
    val sourcePartition = integer("source_partition")
    val sourceOffset = long("source_offset")
    val sykmeldingId = javaUUID("sykmelding_id").nullable()
    val reason = text("reason")
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(sourcePartition, sourceOffset, name = "sykmeldingsregister_dead_letter_pkey")

    init {
        index("sykmeldingsregister_dead_letter_sykmelding_id_idx", false, sykmeldingId)
    }
}
