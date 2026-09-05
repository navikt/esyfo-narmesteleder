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
import kotlinx.coroutines.CancellationException
import no.nav.syfo.application.exception.ApiErrorException
import org.slf4j.LoggerFactory
import java.util.*

const val NAV_CALL_ID_HEADER = "Nav-Call-Id"
internal const val STATUS_PAGES_LOGGER_NAME = "no.nav.syfo.application.api.StatusPages"

private val statusPagesLogger = LoggerFactory.getLogger(STATUS_PAGES_LOGGER_NAME)

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

private fun logException(cause: Throwable) {
    if (cause is ApiErrorException && cause.isAlreadyLogged) {
        return
    }
    statusPagesLogger.warn("Unhandled API exception", cause)
}

fun determineApiError(cause: Throwable, path: String): ApiError = when (cause) {
    is BadRequestException -> cause.toApiError(path)
    is NotFoundException -> cause.toApiError(path)
    is ApiErrorException -> cause.toApiError(path)
    else -> ApiError(
        HttpStatusCode.InternalServerError,
        ErrorType.INTERNAL_SERVER_ERROR,
        cause.message ?: "Internal server error",
        path
    )
}

fun Application.installStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) {
                throw cause
            }
            val apiError = determineApiError(cause, call.request.path())
            logException(cause)
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
