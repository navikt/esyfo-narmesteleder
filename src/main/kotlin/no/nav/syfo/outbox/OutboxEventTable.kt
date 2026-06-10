package no.nav.syfo.outbox

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object OutboxEventTable : Table("outbox_event") {
    val id = javaUUID("id").databaseGenerated()
    val destination = text("destination")
    val eventType = text("event_type")
    val kafkaKey = text("kafka_key")
    val payload = text("payload")
    val payloadVersion = integer("payload_version")
    val status = text("status")
    val attemptCount = integer("attempt_count")
    val claimId = javaUUID("claim_id").nullable()
    val lockedAt = timestampWithTimeZone("locked_at").nullable()
    val lockedBy = text("locked_by").nullable()
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at").nullable()
    val sentAt = timestampWithTimeZone("sent_at").nullable()
    val lastError = text("last_error").nullable()
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    val updated = timestampWithTimeZone("updated").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}
