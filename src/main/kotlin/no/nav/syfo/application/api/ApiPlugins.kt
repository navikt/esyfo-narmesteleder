package no.nav.syfo.application.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.util.*
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType

const val NAV_CALL_ID_HEADER = "Nav-Call-Id"

fun Application.installContentNegotiation() {
    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }
}fun Application.installCallId() {
    install(CallId) {
        retrieve { it.request.headers[NAV_CALL_ID_HEADER] }
        generate { UUID.randomUUID().toString() }
        verify { callId: String -> callId.isNotEmpty() }
        header(NAV_CALL_ID_HEADER)
    }
}

private fun logException(call: ApplicationCall, cause: Throwable) {
    val logExceptionMessage = "Caught ${cause::class.simpleName} exception"
    call.application.log.warn(logExceptionMessage, cause)
}

fun determineApiError(cause: Throwable, path: String): ApiError {
    return when (cause) {
        is BadRequestException -> cause.toApiError(path)
        is NotFoundException -> cause.toApiError(path)
        else -> ApiError(
            HttpStatusCode.InternalServerError,
            ErrorType.INTERNAL_SERVER_ERROR,
            cause.message ?: "Internal server error",
            path
        )
    }
}

fun Application.installStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logException(call, cause)
            val apiError = determineApiError(cause, call.request.path())
            call.respond(apiError.status, apiError)
        }
    }
}

fun BadRequestException.toApiError(path: String?): ApiError {
    return ApiError(
        status = HttpStatusCode.BadRequest,
        type = ErrorType.BAD_REQUEST,
        message = this.message ?: "Bad request",
        path = path
    )
}

fun NotFoundException.toApiError(path: String?): ApiError {
    return ApiError(
        status = HttpStatusCode.NotFound,
        type = ErrorType.NOT_FOUND,
        message = this.message ?: "Bad request",
        path = path
    )
}
