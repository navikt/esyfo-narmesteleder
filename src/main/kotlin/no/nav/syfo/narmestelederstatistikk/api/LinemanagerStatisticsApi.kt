package no.nav.syfo.narmestelederstatistikk.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederstatistikk.application.GetNarmestelederstatistikkResult
import no.nav.syfo.narmestelederstatistikk.application.GetNarmestelederstatistikkUseCase
import no.nav.syfo.narmestelederstatistikk.observability.countLinemanagerStatistics
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINEMANAGER_STATISTICS_API_PATH = "/linemanager/statistics"

fun Route.registerLinemanagerStatisticsApi(
    getNarmestelederstatistikk: GetNarmestelederstatistikkUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(LINEMANAGER_STATISTICS_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        get {
            val organizationNumber = call.requiredOrganizationNumberQueryParameter("orgNumber")
            val principal = call.getMyPrincipal()
            when (val result = getNarmestelederstatistikk.execute(organizationNumber, principal.toOrganizationAccessSubject())) {
                is GetNarmestelederstatistikkResult.Found -> {
                    countLinemanagerStatistics(principal)
                    call.respond(HttpStatusCode.OK, result.statistikk.toResponse())
                }
                is GetNarmestelederstatistikkResult.AccessDenied -> throw result.reason.toForbiddenException(result.organizationNumber)
            }
        }
    }
}

private fun RoutingCall.requiredOrganizationNumberQueryParameter(name: String): OrganizationNumber {
    val value = queryParameters[name] ?: throw ApiErrorException.BadRequestException("Missing $name parameter")
    return runCatching { OrganizationNumber(value) }.getOrElse {
        throw ApiErrorException.BadRequestException(
            it.message ?: "Invalid organization number format for $name parameter",
            type = ErrorType.INVALID_FORMAT,
        )
    }
}
