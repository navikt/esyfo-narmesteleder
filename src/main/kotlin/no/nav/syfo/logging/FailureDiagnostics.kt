package no.nav.syfo.logging

import com.fasterxml.jackson.core.JsonProcessingException
import io.ktor.client.plugins.ResponseException
import no.nav.esyfo.observability.causeChain
import no.nav.esyfo.observability.causeType
import no.nav.esyfo.observability.exceptionType
import no.nav.esyfo.observability.sqlState
import no.nav.esyfo.observability.validUpstreamStatus
import no.nav.syfo.application.exception.UpstreamExceptionType
import no.nav.syfo.application.exception.UpstreamRequestException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/** Technical exception metadata only: request bodies, tokens and exception messages are never copied. */
internal data class FailureDiagnostics(
    val exceptionType: String,
    val causeType: String,
    val causeTypes: List<String>,
    val failureKind: String,
    val upstreamStatus: Int?,
    val upstream: String?,
    val failureStage: String?,
    val stack: Throwable,
    val sqlState: String?,
)

internal fun Throwable.failureDiagnostics(): FailureDiagnostics {
    val chain = causeChain()
    val upstreamStatus = chain.firstNotNullOfOrNull {
        when (it) {
            is UpstreamRequestException -> it.upstreamStatus
            is ResponseException -> it.response.status.value
            else -> null
        }
    }
    val kind = when {
        chain.any { it is UnknownHostException } -> "dns"
        chain.any { it is SocketTimeoutException || it.javaClass.simpleName in TIMEOUT_TYPES } -> "timeout"
        chain.any { it is SSLException } -> "tls"
        chain.any { it is ConnectException || it is SocketException } -> "connection"
        chain.any {
            it is JsonProcessingException ||
                it.javaClass.simpleName in DECODING_TYPES ||
                it is UpstreamRequestException &&
                it.upstreamExceptionType == UpstreamExceptionType.RESPONSE_DECODING_EXCEPTION
        } -> "invalid_response"
        chain.any { it is ResponseException } || upstreamStatus != null -> "http"
        else -> "unknown"
    }
    return FailureDiagnostics(
        exceptionType = exceptionType(),
        causeType = causeType(),
        causeTypes = chain.map { it.safeTypeName() },
        failureKind = kind,
        upstreamStatus = validUpstreamStatus(upstreamStatus),
        upstream = chain.firstNotNullOfOrNull {
            when (it) {
                is UpstreamRequestException -> it.upstream
                is SQLException -> "database"
                else -> null
            }
        },
        failureStage = chain.filterIsInstance<UpstreamRequestException>().firstOrNull()?.failureStage?.logValue
            ?: if (kind in RESPONSE_FAILURE_KINDS) "response" else null,
        stack = chain.toTechnicalFailure(),
        sqlState = sqlState(),
    )
}

internal fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}

/** Copies class names and stack frames only, so exception messages never reach the log. */
private class TechnicalFailure(type: String, cause: Throwable?) : RuntimeException(type, cause)

private fun List<Throwable>.toTechnicalFailure(): Throwable {
    val original = first()
    return TechnicalFailure(original.javaClass.name, drop(1).takeIf { it.isNotEmpty() }?.toTechnicalFailure()).apply {
        stackTrace = original.stackTrace.take(80).toTypedArray()
    }
}

private val TIMEOUT_TYPES = setOf("HttpRequestTimeoutException", "ConnectTimeoutException", "TimeoutException")
private val DECODING_TYPES = setOf(
    "JsonConvertException",
    "NoTransformationFoundException",
    "SerializationException",
    "RecordDeserializationException",
)
private val RESPONSE_FAILURE_KINDS = setOf("http", "invalid_response")

private val TYPE_NAME = Regex("^[A-Za-z][A-Za-z0-9]{0,79}$")
private fun Throwable.safeTypeName(): String = javaClass.simpleName.takeIf(TYPE_NAME::matches) ?: "Throwable"
