package no.nav.syfo.integration

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import no.nav.syfo.application.texas.TexasEnvironment
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault

internal const val TEST_SYSTEM_TOKEN = "system-token"

internal val respondWithSystemToken: MockRequestHandler = {
    respondJson("""{"access_token":"$TEST_SYSTEM_TOKEN","expires_in":3600,"token_type":"Bearer"}""")
}

/** Routes Texas token requests to [token] and every other request to [upstream]. */
internal fun upstreamHttpClient(
    token: MockRequestHandler,
    upstream: MockRequestHandler,
): HttpClient = httpClientDefault(
    HttpClient(
        MockEngine { request ->
            if (request.url.toString() == texasEnvironment.tokenEndpoint) token(request) else upstream(request)
        }
    )
)

internal fun texasHttpClient(httpClient: HttpClient) = TexasHttpClient(httpClient, texasEnvironment)

internal fun MockRequestHandleScope.respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)

private val texasEnvironment = TexasEnvironment.createForLocal()
