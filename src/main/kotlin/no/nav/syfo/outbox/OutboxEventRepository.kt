package no.nav.syfo.outbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class OutboxEventRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val retryBackoffBase: Duration = DEFAULT_RETRY_BACKOFF_BASE,
    private val retryBackoffMax: Duration = DEFAULT_RETRY_BACKOFF_MAX,
) {
    suspend fun persist(event: PersistOutboxEvent): UUID = inTransaction {
        persistInTransaction(event)
    }

    suspend fun <T> inTransaction(block: () -> T): T = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            block()
        }
    }

    fun persistInTransaction(event: PersistOutboxEvent): UUID {
        val now = now()
        val row = OutboxEventTable.insertReturning(listOf(OutboxEventTable.id)) {
            it[OutboxEventTable.destination] = event.destination.dbValue
            it[OutboxEventTable.eventType] = event.eventType.dbValue
            it[OutboxEventTable.kafkaKey] = event.kafkaKey
            it[OutboxEventTable.payload] = event.payload
            it[OutboxEventTable.payloadVersion] = event.payloadVersion
            it[OutboxEventTable.status] = OutboxEventStatus.PENDING.name
            it[OutboxEventTable.attemptCount] = 0
            it[OutboxEventTable.nextAttemptAt] = now
        }.single()

        return row[OutboxEventTable.id]
    }

    suspend fun claimById(id: UUID, lockedBy: String): OutboxEvent? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val candidate = OutboxEventTable
                .selectAll()
                .where {
                    (OutboxEventTable.id eq id) and
                        (OutboxEventTable.status inList CLAIMABLE_STATUSES)
                }
                .forUpdate(
                    ForUpdateOption.PostgreSQL.ForUpdate(
                        ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED,
                    ),
                )
                .limit(1)
                .singleOrNull()
                ?: return@suspendTransaction null

            claim(candidate, lockedBy)
        }
    }

    suspend fun claimPending(batchSize: Int, lockedBy: String): List<OutboxEvent> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val candidates = OutboxEventTable
                .selectAll()
                .where {
                    (OutboxEventTable.status inList CLAIMABLE_STATUSES) and
                        (
                            OutboxEventTable.nextAttemptAt.isNull() or
                                (OutboxEventTable.nextAttemptAt lessEq now())
                            )
                }
                .orderBy(OutboxEventTable.nextAttemptAt to SortOrder.ASC, OutboxEventTable.created to SortOrder.ASC)
                .limit(batchSize)
                .forUpdate(
                    ForUpdateOption.PostgreSQL.ForUpdate(
                        ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED,
                    ),
                )
                .map { claim(it, lockedBy) }

            candidates
        }
    }

    suspend fun markSent(id: UUID, expectedLockedBy: String, expectedClaimId: UUID): Int = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            OutboxEventTable.update({
                finalizeWhereClause(
                    id = id,
                    expectedLockedBy = expectedLockedBy,
                    expectedClaimId = expectedClaimId,
                )
            }) {
                it[OutboxEventTable.status] = OutboxEventStatus.SENT.name
                it[OutboxEventTable.claimId] = null
                it[OutboxEventTable.lockedAt] = null
                it[OutboxEventTable.lockedBy] = null
                it[OutboxEventTable.nextAttemptAt] = null
                it[OutboxEventTable.sentAt] = now()
                it[OutboxEventTable.lastError] = null
            }
        }
    }

    suspend fun markRetry(
        id: UUID,
        expectedLockedBy: String,
        expectedClaimId: UUID,
        attemptCount: Int,
        errorMessage: String,
    ): Int = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            OutboxEventTable.update({
                finalizeWhereClause(
                    id = id,
                    expectedLockedBy = expectedLockedBy,
                    expectedClaimId = expectedClaimId,
                )
            }) {
                it[OutboxEventTable.status] = OutboxEventStatus.RETRY.name
                it[OutboxEventTable.claimId] = null
                it[OutboxEventTable.lockedAt] = null
                it[OutboxEventTable.lockedBy] = null
                it[OutboxEventTable.nextAttemptAt] = now().plus(computeRetryBackoff(attemptCount))
                it[OutboxEventTable.lastError] = errorMessage
            }
        }
    }

    suspend fun markDead(
        id: UUID,
        expectedLockedBy: String,
        expectedClaimId: UUID,
        errorMessage: String,
    ): Int = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            OutboxEventTable.update({
                finalizeWhereClause(
                    id = id,
                    expectedLockedBy = expectedLockedBy,
                    expectedClaimId = expectedClaimId,
                )
            }) {
                it[OutboxEventTable.status] = OutboxEventStatus.DEAD.name
                it[OutboxEventTable.claimId] = null
                it[OutboxEventTable.lockedAt] = null
                it[OutboxEventTable.lockedBy] = null
                it[OutboxEventTable.nextAttemptAt] = null
                it[OutboxEventTable.lastError] = errorMessage
            }
        }
    }

    suspend fun requeueStaleProcessing(staleAfter: Duration): Int = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val staleBefore = now().minus(staleAfter)
            OutboxEventTable.update({
                (OutboxEventTable.status eq OutboxEventStatus.PROCESSING.name) and
                    (OutboxEventTable.lockedAt less staleBefore)
            }) {
                it[OutboxEventTable.status] = OutboxEventStatus.RETRY.name
                it[OutboxEventTable.claimId] = null
                it[OutboxEventTable.lockedAt] = null
                it[OutboxEventTable.lockedBy] = null
                it[OutboxEventTable.nextAttemptAt] = now()
                it[OutboxEventTable.lastError] = STALE_REQUEUE_MESSAGE
            }
        }
    }

    suspend fun getById(id: UUID): OutboxEvent? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.id eq id }
                .singleOrNull()
                ?.toOutboxEvent()
        }
    }

    suspend fun findAll(): List<OutboxEvent> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            OutboxEventTable.selectAll()
                .orderBy(OutboxEventTable.created to SortOrder.ASC)
                .map { it.toOutboxEvent() }
        }
    }

    private fun claim(row: ResultRow, lockedBy: String): OutboxEvent {
        val now = now()
        val claimId = UUID.randomUUID()
        val current = row.toOutboxEvent()
        val claimed = current.copy(
            status = OutboxEventStatus.PROCESSING,
            attemptCount = current.attemptCount + 1,
            claimId = claimId,
            lockedAt = now,
            lockedBy = lockedBy,
        )

        OutboxEventTable.update({ OutboxEventTable.id eq current.id }) {
            it[OutboxEventTable.status] = OutboxEventStatus.PROCESSING.name
            it[OutboxEventTable.attemptCount] = claimed.attemptCount
            it[OutboxEventTable.claimId] = claimId
            it[OutboxEventTable.lockedAt] = now
            it[OutboxEventTable.lockedBy] = lockedBy
        }

        return claimed
    }

    private fun finalizeWhereClause(
        id: UUID,
        expectedLockedBy: String,
        expectedClaimId: UUID,
    ) = (OutboxEventTable.id eq id) and
        (OutboxEventTable.status eq OutboxEventStatus.PROCESSING.name) and
        (OutboxEventTable.lockedBy eq expectedLockedBy) and
        (OutboxEventTable.claimId eq expectedClaimId)

    private fun computeRetryBackoff(attemptCount: Int): Duration {
        val multiplier = 1L shl (attemptCount - 1).coerceAtLeast(0)
        val candidate = retryBackoffBase.multipliedBy(multiplier)
        return if (candidate > retryBackoffMax) retryBackoffMax else candidate
    }

    private fun ResultRow.toOutboxEvent(): OutboxEvent = OutboxEvent(
        id = this[OutboxEventTable.id],
        destination = this[OutboxEventTable.destination],
        eventType = this[OutboxEventTable.eventType],
        kafkaKey = this[OutboxEventTable.kafkaKey],
        payload = this[OutboxEventTable.payload],
        payloadVersion = this[OutboxEventTable.payloadVersion],
        status = OutboxEventStatus.valueOf(this[OutboxEventTable.status]),
        attemptCount = this[OutboxEventTable.attemptCount],
        claimId = this[OutboxEventTable.claimId],
        lockedAt = this[OutboxEventTable.lockedAt],
        lockedBy = this[OutboxEventTable.lockedBy],
        nextAttemptAt = this[OutboxEventTable.nextAttemptAt],
        sentAt = this[OutboxEventTable.sentAt],
        lastError = this[OutboxEventTable.lastError],
        created = this[OutboxEventTable.created],
        updated = this[OutboxEventTable.updated],
    )

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    companion object {
        private val CLAIMABLE_STATUSES = listOf(
            OutboxEventStatus.PENDING.name,
            OutboxEventStatus.RETRY.name,
        )
        val DEFAULT_RETRY_BACKOFF_BASE: Duration = Duration.ofSeconds(30)
        val DEFAULT_RETRY_BACKOFF_MAX: Duration = Duration.ofMinutes(15)
        const val STALE_REQUEUE_MESSAGE = "Requeued stale outbox claim"
    }
}
