package no.nav.syfo.narmestelederbehov.infrastructure

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object DialogDispatchTable : Table("nl_dialog_dispatch") {
    val behovId = javaUUID("behov_id")
    val status = text("status")
    val revision = long("revision")
    val availableAt = timestampWithTimeZone("available_at")
    val claimToken = javaUUID("claim_token").nullable()
    val createStarted = bool("create_started")
    val intendedExpiry = timestampWithTimeZone("intended_expiry").nullable()
    val attempts = integer("attempts")
    val pendingSince = timestampWithTimeZone("pending_since").nullable()
    val lastFailure = text("last_failure").nullable()
    override val primaryKey = PrimaryKey(behovId)
}
