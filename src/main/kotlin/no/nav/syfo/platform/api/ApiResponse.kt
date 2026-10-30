package no.nav.syfo.platform.api

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond
import no.nav.syfo.platform.upstream.UpstreamFailure

data class ApiRejection(
    val error: ApiError,
    val alreadyLogged: Boolean = false,
    val upstreamFailure: UpstreamFailure? = null,
)

sealed interface ApiResponse {
    data class Success(val status: HttpStatusCode, val body: Any? = null) : ApiResponse
    data class Rejected(val rejection: ApiRejection) : ApiResponse
}

/** Right becomes the response body; `Unit` means no body. */
fun <T> Either<ApiRejection, T>.toApiResponse(status: HttpStatusCode): ApiResponse = fold(
    ifLeft = { ApiResponse.Rejected(it) },
    ifRight = { ApiResponse.Success(status, it.takeUnless { body -> body == Unit }) },
)

suspend fun ApplicationCall.respondApi(response: ApiResponse) {
    when (response) {
        is ApiResponse.Success -> {
            val body = response.body
            if (body != null) {
                respond(response.status, body)
            } else {
                respond(response.status)
            }
        }

        is ApiResponse.Rejected -> {
            val rejection = response.rejection
            val error = rejection.error
            logApiError(error, rejection.upstreamFailure, rejection.upstreamFailure?.cause, rejection.alreadyLogged)
            respond(
                error.status,
                ApiError(error.status, error.type, error.message, request.path(), error.timestamp),
            )
        }
    }
}
