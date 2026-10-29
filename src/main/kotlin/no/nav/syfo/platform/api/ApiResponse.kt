package no.nav.syfo.platform.api

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import no.nav.syfo.platform.upstream.UpstreamFailure
import io.ktor.server.response.respond as respondKtor

data class ApiRejection(
    val error: ApiError,
    val alreadyLogged: Boolean = false,
    val upstreamFailure: UpstreamFailure? = null,
    val cause: Throwable? = null,
)

sealed interface ApiResponse {
    data class Success(val status: HttpStatusCode, val body: Any? = null) : ApiResponse
    data class Rejected(val rejection: ApiRejection) : ApiResponse
}

fun <E, T> Either<E, T>.toApiResponse(
    status: HttpStatusCode,
    body: (T) -> Any? = { null },
    toRejection: (E) -> ApiRejection,
): ApiResponse = fold(
    ifLeft = { ApiResponse.Rejected(toRejection(it)) },
    ifRight = { ApiResponse.Success(status, body(it)) },
)

suspend fun ApplicationCall.respond(response: ApiResponse) {
    when (response) {
        is ApiResponse.Success -> {
            val body = response.body
            if (body != null) {
                respondKtor(response.status, body)
            } else {
                respondKtor(response.status)
            }
        }

        is ApiResponse.Rejected -> {
            val rejection = response.rejection
            val error = rejection.error
            logApiError(error, rejection.upstreamFailure, rejection.cause, rejection.alreadyLogged)
            respondKtor(
                error.status,
                ApiError(error.status, error.type, error.message, request.path(), error.timestamp),
            )
        }
    }
}
