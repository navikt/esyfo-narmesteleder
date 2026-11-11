package no.nav.syfo.sykmelding.infrastructure

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Sykmeldingsregister records that could not be stored, keyed by Kafka position so a
 * redelivered record is written once. `reason` has no CHECK constraint: a constraint violation
 * would be retried instead of dead-lettered and could stop the consumer.
 */
internal object SykmeldingsregisterDeadLetterTable : Table("sykmeldingsregister_dead_letter") {
    val sourcePartition = integer("source_partition")
    val sourceOffset = long("source_offset")
    val reason = text("reason")
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(sourcePartition, sourceOffset, name = "sykmeldingsregister_dead_letter_pkey")
}
