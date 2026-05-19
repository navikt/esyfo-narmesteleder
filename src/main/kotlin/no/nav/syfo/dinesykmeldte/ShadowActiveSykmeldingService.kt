package no.nav.syfo.dinesykmeldte

import io.micrometer.core.instrument.Counter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.sykmelding.exposed.IActiveSykmeldingRepository
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
    override suspend fun getIsActiveSykmelding(personIdent: String, orgnummer: String): Boolean = coroutineScope {
        val clientResultDeferred = async {
            suspendRunCatching {
                dinesykmeldteService.getIsActiveSykmelding(personIdent, orgnummer)
            }
        }
        val localResultDeferred = async {
            suspendRunCatching {
                repository.hasActiveSykmelding(personIdent, orgnummer)
            }
        }

        val clientResult = clientResultDeferred.await()
        val localResult = localResultDeferred.await()

        if (clientResult.isSuccess) {
            return@coroutineScope handleSuccessfulClientResult(
                clientValue = clientResult.getOrThrow(),
                localResult = localResult,
                personIdent = personIdent,
                orgnummer = orgnummer,
            )
        } else {
            handleFailedClientResult(
                clientException = clientResult.exceptionOrNull() ?: error("Missing client exception"),
                localResult = localResult,
                personIdent = personIdent,
                orgnummer = orgnummer,
            )
        }
    }

    private fun handleSuccessfulClientResult(
        clientValue: Boolean,
        localResult: Result<Boolean>,
        personIdent: String,
        orgnummer: String,
    ): Boolean {
        localResult
            .onSuccess { logMismatchIfAny(clientValue, localResult.getOrThrow(), personIdent, orgnummer) }
            .onFailure { localException -> logLocalShadowQueryFailed(personIdent, orgnummer, localException) }
        return clientValue
    }

    private fun handleFailedClientResult(
        clientException: Throwable,
        localResult: Result<Boolean>,
        personIdent: String,
        orgnummer: String,
    ): Boolean {
        logClientFailedUsingLocalFallback(
            personIdent = personIdent,
            orgnummer = orgnummer,
            clientException = clientException,
        )

        return localResult.getOrElse { localException ->
            logLocalShadowQueryAlsoFailed(
                personIdent = personIdent,
                orgnummer = orgnummer,
                localException = localException,
            )
            throw clientException
        }
    }

    private fun logMismatchIfAny(
        clientValue: Boolean,
        localValue: Boolean,
        personIdent: String,
        orgnummer: String,
    ) {
        if (clientValue != localValue) {
            countMismatch(clientValue, localValue).increment()
            logger.warn(
                "Shadow mismatch for active sykmelding for fnr={} and orgnummer={}: client={}, local={}",
                maskFnr(personIdent),
                orgnummer,
                clientValue,
                localValue,
            )
        }
    }

    private fun logLocalShadowQueryFailed(
        personIdent: String,
        orgnummer: String,
        localException: Throwable,
    ) {
        logger.warn(
            "Local shadow query failed for fnr={} and orgnummer={}, ignoring local result. Exception type={}",
            maskFnr(personIdent),
            orgnummer,
            localException::class.simpleName ?: "UnknownException",
        )
    }

    private fun logClientFailedUsingLocalFallback(
        personIdent: String,
        orgnummer: String,
        clientException: Throwable,
    ) {
        logger.warn(
            "Dinesykmeldte client failed for fnr={} and orgnummer={}, using local fallback. Exception type={}",
            maskFnr(personIdent),
            orgnummer,
            clientException::class.simpleName ?: "UnknownException",
        )
    }

    private fun logLocalShadowQueryAlsoFailed(
        personIdent: String,
        orgnummer: String,
        localException: Throwable,
    ) {
        logger.warn(
            "Local shadow query also failed for fnr={} and orgnummer={}, rethrowing client exception. Exception type={}",
            maskFnr(personIdent),
            orgnummer,
            localException::class.simpleName ?: "UnknownException",
        )
    }

    private fun countMismatch(clientValue: Boolean, localValue: Boolean): Counter = when {
        clientValue && !localValue -> COUNT_ACTIVE_SYKMELDING_SHADOW_MISMATCH_CLIENT_TRUE_LOCAL_FALSE
        !clientValue && localValue -> COUNT_ACTIVE_SYKMELDING_SHADOW_MISMATCH_CLIENT_FALSE_LOCAL_TRUE
        else -> error("Unexpected shadow mismatch combination")
    }

    private fun maskFnr(personIdent: String): String = "****${personIdent.takeLast(4)}"

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
