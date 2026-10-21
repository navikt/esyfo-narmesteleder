package no.nav.syfo.integration

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.http.httpClientDefault

internal const val TEST_SYSTEM_TOKEN = "system-token"

internal val respondWithSystemToken: MockRequestHandler = {
    respondJson("""{"access_token":"$TEST_SYSTEM_TOKEN","expires_in":3600,"token_type":"Bearer"}""")
}

/** Routes Texas token requests to [token] and every other request to [upstream]. */
internal fun upstreamHttpClient(
    token: MockRequestHandler,
    upstream: MockRequestHandler,
): HttpClient = httpClientDefaultWithoutRetryDelay(
    MockEngine { request ->
        if (request.url.toString() in setOf(texasEnvironment.tokenEndpoint, texasEnvironment.tokenExchangeEndpoint)) token(request) else upstream(request)
    }
)

/**
 * [httpClientDefault] on [engine] with the production retry policy, but without the real wait between attempts.
 * The attempts and the retry predicate are unchanged, so failure-path tests still exercise the retries.
 */
internal fun httpClientDefaultWithoutRetryDelay(engine: HttpClientEngine): HttpClient = httpClientDefault(HttpClient(engine)).config {
    install(HttpRequestRetry) { delay { } }
}

internal fun texasHttpClient(httpClient: HttpClient) = TexasHttpClient(httpClient, texasEnvironment)

internal fun MockRequestHandleScope.respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)

private val texasEnvironment = TexasEnvironment.createForLocal()
