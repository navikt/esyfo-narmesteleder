package no.nav.syfo.narmesteleder.api.v1

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchRequest
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINEMANAGER_SEARCH_API_PATH = "/linemanager/search"

private val strictLinemanagerSearchRequestMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

fun Route.registerLinemanagerSearchApi(
    texasHttpClient: TexasHttpClient,
    linemanagerSearchService: LinemanagerSearchService,
) {
    route(LINEMANAGER_SEARCH_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        post {
            val principal = call.getMyPrincipal()
            val collection = linemanagerSearchService.search(
                request = call.receiveLinemanagerSearchRequest(),
                principal = principal,
            )
            countLinemanagerSearch(principal)
            call.respond(HttpStatusCode.OK, collection)
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingCall.receiveLinemanagerSearchRequest(): LinemanagerSearchRequest = try {
    strictLinemanagerSearchRequestMapper.readValue(receiveText())
} catch (exception: UnrecognizedPropertyException) {
    throw ApiErrorException.BadRequestException(
        errorMessage = "Invalid search request. Unknown field: ${exception.propertyName}",
        type = ErrorType.INVALID_FORMAT,
    )
} catch (_: JacksonException) {
    throw ApiErrorException.BadRequestException(
        errorMessage = "Invalid search request",
        type = ErrorType.INVALID_FORMAT,
    )
}
