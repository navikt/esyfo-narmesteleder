package no.nav.syfo.logging

import no.nav.esyfo.observability.ApplicationLogger
import no.nav.esyfo.observability.Event
import no.nav.esyfo.observability.createLogger
import org.slf4j.Logger
import org.slf4j.event.Level

internal data class EventContext<T>(val details: T, val failure: FailureDiagnostics? = null)

private fun <T> failureFields(): Map<String, (EventContext<T>) -> Any?> = mapOf(
    "failure_kind" to { it.failure?.failureKind },
    "exception_type" to { it.failure?.exceptionType },
    "cause_type" to { it.failure?.causeType },
    "cause_types" to { it.failure?.causeTypes },
    "upstream_status" to { it.failure?.upstreamStatus },
    "sql_state" to { it.failure?.sqlState },
)

internal fun <T> applicationEvent(
    name: String,
    level: Level,
    message: String,
    upstream: String? = null,
    operation: String? = null,
    errorCode: ((EventContext<T>) -> String?)? = null,
    fields: Map<String, (T) -> Any?> = emptyMap(),
): Event<EventContext<T>> {
    val commonFields: Map<String, (EventContext<T>) -> Any?> = mapOf(
        "upstream" to { it.failure?.upstream ?: upstream },
        "failure_stage" to { it.failure?.failureStage },
    )
    val detailFields = fields.mapValues { (_, field) ->
        { context: EventContext<T> -> field(context.details) }
    }
    return Event(
        name = name,
        level = level,
        message = message,
        operation = operation,
        errorCodeFrom = errorCode,
        fields = commonFields + failureFields<T>() + detailFields,
    )
}

internal fun <T> ApplicationLogger.logEvent(event: Event<EventContext<T>>, details: T, cause: Throwable? = null) {
    val failure = cause?.failureDiagnostics()
    event(event, EventContext(details, failure), failure?.stack)
}

internal fun <T> Logger.logEvent(
    event: Event<EventContext<T>>,
    details: T,
    cause: Throwable? = null,
) = createLogger(this).logEvent(event, details, cause)
