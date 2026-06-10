package no.nav.syfo.outbox

import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import java.time.OffsetDateTime
import java.util.UUID

enum class OutboxDestination(val dbValue: String, val metricValue: String) {
    SYKMELDING_NL("SYKMELDING_NL", "sykmelding_nl"),
    UNKNOWN("UNKNOWN", "unknown");

    companion object {
        fun fromDbValue(value: String): OutboxDestination? = entries.firstOrNull { it.dbValue == value }
    }
}

enum class OutboxEventType(val dbValue: String, val metricValue: String) {
    NL_RELASJON("NL_RELASJON", "nl_relasjon"),
    NL_AVBRUTT("NL_AVBRUTT", "nl_avbrutt"),
    UNKNOWN("UNKNOWN", "unknown");

    companion object {
        fun fromDbValue(value: String): OutboxEventType? = entries.firstOrNull { it.dbValue == value }
    }
}

enum class OutboxEventStatus {
    PENDING,
    PROCESSING,
    SENT,
    RETRY,
    DEAD,
}

data class PersistOutboxEvent(
    val destination: OutboxDestination,
    val eventType: OutboxEventType,
    val kafkaKey: String,
    val payload: String,
    val payloadVersion: Int,
)

data class OutboxEvent(
    val id: UUID,
    val destination: String,
    val eventType: String,
    val kafkaKey: String,
    val payload: String,
    val payloadVersion: Int,
    val status: OutboxEventStatus,
    val attemptCount: Int,
    val claimId: UUID?,
    val lockedAt: OffsetDateTime?,
    val lockedBy: String?,
    val nextAttemptAt: OffsetDateTime?,
    val sentAt: OffsetDateTime?,
    val lastError: String?,
    val created: OffsetDateTime,
    val updated: OffsetDateTime,
) {
    fun parsedDestination(): OutboxDestination? = OutboxDestination.fromDbValue(destination)
    fun parsedEventType(): OutboxEventType? = OutboxEventType.fromDbValue(eventType)
    fun metricDestination(): String = parsedDestination()?.metricValue ?: OutboxDestination.UNKNOWN.metricValue
    fun metricEventType(): String = parsedEventType()?.metricValue ?: OutboxEventType.UNKNOWN.metricValue
}

data class OutboxNlRelasjonPayload(
    val nlResponse: NlResponse,
    val source: NlResponseSource,
)

data class OutboxNlAvbruttPayload(
    val nlAvbrutt: NlAvbrutt,
    val source: NlResponseSource,
)
