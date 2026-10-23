package no.nav.syfo.platform.upstream

import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import kotlin.coroutines.cancellation.CancellationException

internal suspend fun requestUpstream(
    upstream: UpstreamName,
    request: suspend () -> HttpResponse,
): UpstreamResult<HttpResponse> = try {
    UpstreamResult.Success(request())
} catch (e: CancellationException) {
    throw e
} catch (e: ResponseException) {
    UpstreamResult.Failure(UpstreamFailure(upstream, UpstreamFailureStage.RESPONSE, e.response.status.value, e))
} catch (e: Exception) {
    UpstreamResult.Failure(UpstreamFailure(upstream, UpstreamFailureStage.REQUEST, null, e))
}

internal suspend fun <T> HttpResponse.decodeUpstream(
    upstream: UpstreamName,
    decode: suspend HttpResponse.() -> T,
): UpstreamResult<T> = try {
    UpstreamResult.Success(decode())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    UpstreamResult.Failure(UpstreamFailure(upstream, UpstreamFailureStage.RESPONSE, status.value, e))
}
