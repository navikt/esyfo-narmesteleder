package no.nav.syfo.narmestelederrelasjon.api

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
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerSearchRequest
import no.nav.syfo.narmestelederrelasjon.api.model.toResponse
import no.nav.syfo.narmestelederrelasjon.application.SearchActiveNarmestelederrelasjonerCommand
import no.nav.syfo.narmestelederrelasjon.application.SearchActiveNarmestelederrelasjonerResult
import no.nav.syfo.narmestelederrelasjon.application.SearchActiveNarmestelederrelasjonerUseCase
import no.nav.syfo.narmestelederrelasjon.observability.countLinemanagerSearch
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINEMANAGER_SEARCH_API_PATH = "/linemanager/search"

private val strictLinemanagerSearchRequestMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

fun Route.registerLinemanagerSearchApi(
    searchActiveNarmestelederrelasjoner: SearchActiveNarmestelederrelasjonerUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(LINEMANAGER_SEARCH_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        post {
            val principal = call.getMyPrincipal()
            val command = call.receiveLinemanagerSearchRequest().toCommand()
            val result = searchActiveNarmestelederrelasjoner.execute(
                subject = principal.toOrganizationAccessSubject(),
                command = command,
            )
            val collection = when (result) {
                is SearchActiveNarmestelederrelasjonerResult.AccessDenied -> throw result.reason.toForbiddenException(command.orgNumber)
                SearchActiveNarmestelederrelasjonerResult.InvalidText ->
                    throw ApiErrorException.BadRequestException("text must be at most 50 characters")
                SearchActiveNarmestelederrelasjonerResult.InvalidPageToken ->
                    throw ApiErrorException.BadRequestException("Invalid pageToken", type = ErrorType.INVALID_FORMAT)
                is SearchActiveNarmestelederrelasjonerResult.Success -> result.toResponse()
            }
            countLinemanagerSearch(principal)
            call.respond(HttpStatusCode.OK, collection)
        }
    }
}

private fun LinemanagerSearchRequest.toCommand(): SearchActiveNarmestelederrelasjonerCommand = runCatching {
    SearchActiveNarmestelederrelasjonerCommand(
        orgNumber = OrganizationNumber(orgNumber),
        managerNationalIdentificationNumber = managerNationalIdentificationNumber?.let(::PersonIdent),
        employeeNationalIdentificationNumber = employeeNationalIdentificationNumber?.let(::PersonIdent),
        hasActiveSickLeave = hasActiveSickLeave,
        text = text,
        pageSize = pageSize,
        pageToken = pageToken,
    )
}.getOrElse {
    throw ApiErrorException.BadRequestException("Invalid search request", type = ErrorType.INVALID_FORMAT)
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
