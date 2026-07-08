package no.nav.syfo.dinesykmeldte

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.sykmelding.exposed.IActiveSykmeldingRepository
import no.nav.syfo.sykmelding.exposed.LocalActiveSykmeldingResult
import no.nav.syfo.util.logger
import kotlin.coroutines.cancellation.CancellationException

private const val ACTIVE_SYKMELDING_SHADOW_MISMATCH_TOTAL =
    "${METRICS_NS}_active_sykmelding_shadow_mismatch_total"
private const val SHADOW_DIRECTION_CLIENT_TRUE_LOCAL_FALSE = "client_true_local_false"
private const val SHADOW_DIRECTION_CLIENT_FALSE_LOCAL_TRUE = "client_false_local_true"

val COUNT_ACTIVE_SYKMELDING_SHADOW_MISMATCH_CLIENT_TRUE_LOCAL_FALSE: Counter = Counter
    .builder(ACTIVE_SYKMELDING_SHADOW_MISMATCH_TOTAL)
    .description("Counts active sykmelding shadow mismatches where client=true and local=false")
    .tag("direction", SHADOW_DIRECTION_CLIENT_TRUE_LOCAL_FALSE)
    .register(METRICS_REGISTRY)

val COUNT_ACTIVE_SYKMELDING_SHADOW_MISMATCH_CLIENT_FALSE_LOCAL_TRUE: Counter = Counter
    .builder(ACTIVE_SYKMELDING_SHADOW_MISMATCH_TOTAL)
    .description("Counts active sykmelding shadow mismatches where client=false and local=true")
    .tag("direction", SHADOW_DIRECTION_CLIENT_FALSE_LOCAL_TRUE)
    .register(METRICS_REGISTRY)

class ShadowActiveSykmeldingService(
    private val dinesykmeldteService: DinesykmeldteService,
    private val repository: IActiveSykmeldingRepository,
) : IDinesykmeldteService {
    override suspend fun getIsActiveSykmelding(personIdent: String, orgnummer: String): Boolean {
        val clientResult = suspendRunCatching {
            dinesykmeldteService.getIsActiveSykmelding(personIdent, orgnummer)
        }
        if (clientResult.isSuccess) {
            val localResult = suspendRunCatching {
                repository.findActiveSykmelding(personIdent, orgnummer)
            }
            return handleSuccessfulClientResult(
                clientValue = clientResult.getOrThrow(),
                localResult = localResult,
            )
        } else {
            handleFailedClientResult(
                clientException = clientResult.exceptionOrNull() ?: error("Missing client exception"),
            )
        }
    }

    private fun handleSuccessfulClientResult(
        clientValue: Boolean,
        localResult: Result<LocalActiveSykmeldingResult>,
    ): Boolean {
        localResult
            .onSuccess { localValue -> logMismatchIfAny(clientValue, localValue.isActive) }
            .onFailure { localException -> logLocalShadowQueryFailed(localException) }
        return clientValue
    }

    private fun handleFailedClientResult(
        clientException: Throwable,
    ): Nothing {
        logClientFailedRethrowingClientException(
            clientException = clientException,
        )
        throw clientException
    }

    private fun logMismatchIfAny(
        clientValue: Boolean,
        localValue: Boolean,
    ) {
        if (clientValue != localValue) {
            val direction = directionOf(clientValue, localValue)
            countMismatch(direction).increment()
            logger.warn(
                "Shadow mismatch for active sykmelding: direction={}, client={}, local={}",
                direction,
                clientValue,
                localValue,
            )
        }
    }

    private fun logLocalShadowQueryFailed(localException: Throwable) {
        logger.warn(
            "Local shadow query failed, ignoring local result. Exception type={}",
            localException::class.simpleName ?: "UnknownException",
        )
    }

    private fun logClientFailedRethrowingClientException(clientException: Throwable) {
        logger.warn(
            "Dinesykmeldte client failed, rethrowing client exception. Exception type={}",
            clientException::class.simpleName ?: "UnknownException",
        )
    }

    private fun directionOf(clientValue: Boolean, localValue: Boolean): String = when {
        clientValue && !localValue -> SHADOW_DIRECTION_CLIENT_TRUE_LOCAL_FALSE
        !clientValue && localValue -> SHADOW_DIRECTION_CLIENT_FALSE_LOCAL_TRUE
        else -> error("Unexpected shadow mismatch combination")
    }

    private fun countMismatch(direction: String): Counter = when (direction) {
        SHADOW_DIRECTION_CLIENT_TRUE_LOCAL_FALSE -> COUNT_ACTIVE_SYKMELDING_SHADOW_MISMATCH_CLIENT_TRUE_LOCAL_FALSE
        SHADOW_DIRECTION_CLIENT_FALSE_LOCAL_TRUE -> COUNT_ACTIVE_SYKMELDING_SHADOW_MISMATCH_CLIENT_FALSE_LOCAL_TRUE
        else -> error("Unexpected shadow mismatch direction")
    }

    private inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    companion object {
        private val logger = logger()
    }
}
