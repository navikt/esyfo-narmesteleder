package no.nav.syfo.outbox

import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.util.logger
import java.time.Duration
import java.util.UUID

class OutboxDirectSender(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxDispatcher: OutboxDispatcher,
    private val outboxMetrics: OutboxMetrics,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val staleProcessingThreshold: Duration = DEFAULT_STALE_PROCESSING_THRESHOLD,
    instanceId: String = UUID.randomUUID().toString(),
) {
    private val logger = logger()
    private val directLockedBy = "outbox-direct-$instanceId"
    private val schedulerLockedBy = "outbox-scheduler-$instanceId"

    suspend fun sendById(id: UUID) {
        runCatching {
            val claimed = outboxEventRepository.claimById(id, directLockedBy) ?: return
            dispatch(claimed)
        }.onFailure { exception ->
            logger.error("Failed direct outbox send for id=$id", exception)
        }
    }

    suspend fun processPendingBatch(batchSize: Int = DEFAULT_BATCH_SIZE): Int {
        val staleRequeued = outboxEventRepository.requeueStaleProcessing(staleProcessingThreshold)
        outboxMetrics.countStaleRequeue(staleRequeued)

        val claimedEvents = outboxEventRepository.claimPending(batchSize, schedulerLockedBy)
        claimedEvents.forEach { event ->
            runCatching {
                dispatch(event)
            }.onFailure { exception ->
                logger.error("Failed scheduled outbox send for id=${event.id}", exception)
            }
        }
        return claimedEvents.size
    }

    private suspend fun dispatch(event: OutboxEvent) {
        try {
            withContext(Dispatchers.IO) {
                outboxDispatcher.dispatch(event)
            }
            val updatedRows = outboxEventRepository.markSent(
                id = event.id,
                expectedLockedBy = event.requiredLockedBy(),
                expectedClaimId = event.requiredClaimId(),
            )
            if (updatedRows == 1) {
                outboxMetrics.countDispatch(event, OutboxDispatchResult.SENT)
                logger.info("Outbox event sent id=${event.id} destination=${event.destination} eventType=${event.eventType}")
            } else {
                logger.warn(
                    "Skipping sent finalization for outbox event id=${event.id} because claim ownership changed",
                )
            }
        } catch (exception: UnknownOutboxRouteException) {
            handleFailure(event, exception, OutboxDispatchResult.UNKNOWN_ROUTE)
        } catch (exception: JsonProcessingException) {
            handleFailure(event, exception, OutboxDispatchResult.DESERIALIZATION_ERROR)
        } catch (exception: IllegalArgumentException) {
            handleFailure(event, exception, OutboxDispatchResult.DESERIALIZATION_ERROR)
        } catch (exception: Exception) {
            handleFailure(event, exception, OutboxDispatchResult.DISPATCH_ERROR)
        }
    }

    private suspend fun handleFailure(
        event: OutboxEvent,
        exception: Exception,
        result: OutboxDispatchResult,
    ) {
        val errorMessage = exception.toSafeErrorMessage()
        val isPermanentFailure = result == OutboxDispatchResult.UNKNOWN_ROUTE || result == OutboxDispatchResult.DESERIALIZATION_ERROR
        val isDeadLetter = isPermanentFailure || event.attemptCount >= maxAttempts

        if (isDeadLetter) {
            val updatedRows = outboxEventRepository.markDead(
                id = event.id,
                expectedLockedBy = event.requiredLockedBy(),
                expectedClaimId = event.requiredClaimId(),
                errorMessage = errorMessage,
            )
            if (updatedRows == 1) {
                outboxMetrics.countDispatch(event, if (isPermanentFailure) result else OutboxDispatchResult.DEAD)
                logger.error(
                    "Outbox event moved to dead-letter state id=${event.id} destination=${event.destination} " +
                        "eventType=${event.eventType} attempts=${event.attemptCount} error=$errorMessage",
                    exception,
                )
            } else {
                logger.warn(
                    "Skipping dead-letter finalization for outbox event id=${event.id} because claim ownership changed",
                )
            }
        } else {
            val updatedRows = outboxEventRepository.markRetry(
                id = event.id,
                expectedLockedBy = event.requiredLockedBy(),
                expectedClaimId = event.requiredClaimId(),
                attemptCount = event.attemptCount,
                errorMessage = errorMessage,
            )
            if (updatedRows == 1) {
                outboxMetrics.countDispatch(event, OutboxDispatchResult.RETRY)
                logger.warn(
                    "Outbox event scheduled for retry id=${event.id} destination=${event.destination} " +
                        "eventType=${event.eventType} attempts=${event.attemptCount} error=$errorMessage",
                )
            } else {
                logger.warn(
                    "Skipping retry finalization for outbox event id=${event.id} because claim ownership changed",
                )
            }
        }
    }

    private fun OutboxEvent.requiredClaimId(): UUID = requireNotNull(claimId) {
        "Expected claimed outbox event to contain claimId for id=$id"
    }

    private fun OutboxEvent.requiredLockedBy(): String = requireNotNull(lockedBy) {
        "Expected claimed outbox event to contain lockedBy for id=$id"
    }

    private fun Exception.toSafeErrorMessage(): String {
        val type = this::class.simpleName ?: "UnknownException"
        val message = message?.replace(Regex("\\s+"), " ")?.take(MAX_ERROR_MESSAGE_LENGTH)
        return listOfNotNull(type, message).joinToString(": ")
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val DEFAULT_MAX_ATTEMPTS = 5
        val DEFAULT_STALE_PROCESSING_THRESHOLD: Duration = Duration.ofMinutes(5)
        private const val MAX_ERROR_MESSAGE_LENGTH = 512
    }
}
