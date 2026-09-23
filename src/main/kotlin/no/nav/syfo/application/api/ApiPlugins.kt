package no.nav.syfo.application.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinInvalidNullException
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.logging.rethrowCancellation
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.*

const val NAV_CALL_ID_HEADER = "Nav-Call-Id"
internal const val STATUS_PAGES_LOGGER_NAME = "no.nav.syfo.application.api.StatusPages"

private val statusPagesLogger = LoggerFactory.getLogger(STATUS_PAGES_LOGGER_NAME)

private data class RequestFailedDetails(val responseStatus: Int)

private val apiRequestFailed = applicationEvent<RequestFailedDetails>(
    name = "api_request_failed",
    level = Level.ERROR,
    message = "Request failed with an unexpected server error",
    fields = mapOf(
        "response_status" to { it.responseStatus },
    ),
)

private data class RequestInvalidDetails(val responseStatus: Int, val errorType: String)

private val apiRequestInvalid = applicationEvent<RequestInvalidDetails>(
    name = "api_request_invalid",
    level = Level.WARN,
    message = "Request failed with a client error",
    fields = mapOf(
        "response_status" to { it.responseStatus },
        "error_type" to { it.errorType },
    ),
)

fun Application.installContentNegotiation() {
    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }
}

fun Application.installCallId() {
    install(CallId) {
        retrieve { it.request.headers[NAV_CALL_ID_HEADER] }
        generate { UUID.randomUUID().toString() }
        verify { callId: String -> callId.isNotEmpty() }
        header(NAV_CALL_ID_HEADER)
    }
}

private fun logException(cause: Throwable, error: ApiError) {
    if (cause is ApiErrorException && cause.isAlreadyLogged) {
        return
    }
    when {
        error.status.value >= 500 -> statusPagesLogger.logEvent(
            apiRequestFailed,
            RequestFailedDetails(error.status.value),
            cause = cause,
        )
        error.status.value in 400..499 && error.status != HttpStatusCode.NotFound -> statusPagesLogger.logEvent(
            apiRequestInvalid,
            RequestInvalidDetails(error.status.value, error.type.name),
        )
        else -> statusPagesLogger.info("Request rejected with status {}", error.status.value)
    }
}

fun determineApiError(cause: Throwable, path: String): ApiError = when (cause) {
    is BadRequestException -> cause.toApiError(path)
    is NotFoundException -> cause.toApiError(path)
    is ApiErrorException -> cause.toApiError(path)
    else -> ApiError(
        HttpStatusCode.InternalServerError,
        ErrorType.INTERNAL_SERVER_ERROR,
        "Internal server error",
        path
    )
}

fun Application.installStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            cause.rethrowCancellation()
            val apiError = determineApiError(cause, call.request.path())
            logException(cause, apiError)
            call.respond(apiError.status, apiError)
        }
    }
}

fun BadRequestException.toApiError(path: String?): ApiError {
    val rootCause = this.rootCause()
    return when (rootCause) {
        is KotlinInvalidNullException -> {
            ApiErrorException.BadRequestException(
                "Invalid request body. Missing required field: ${rootCause.propertyName}",
                type = ErrorType.INVALID_FORMAT
            ).toApiError(path ?: "")
        }

        is IllegalArgumentException,
        is ValueInstantiationException -> {
            ApiErrorException.BadRequestException(
                rootCause.message ?: "Invalid request body",
                type = ErrorType.INVALID_FORMAT
            ).toApiError(path ?: "")
        }

        else -> {
            ApiError(
                status = HttpStatusCode.BadRequest,
                type = ErrorType.BAD_REQUEST,
                message = this.message ?: "Bad request",
                path = path
            )
        }
    }
}

fun NotFoundException.toApiError(path: String?): ApiError = ApiError(
    status = HttpStatusCode.NotFound,
    type = ErrorType.NOT_FOUND,
    message = this.message ?: "Bad request",
    path = path
)

fun Throwable.rootCause(): Throwable {
    var root: Throwable = this
    // Loop until the cause is null or the cause is itself (preventing cycles)
    while (root.cause != null && root.cause != root) {
        root = root.cause!!
    }
    return root
}
