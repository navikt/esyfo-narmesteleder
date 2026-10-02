package no.nav.syfo.narmestelederrelasjon.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerRevoke
import no.nav.syfo.narmestelederrelasjon.application.RevokeActiveNarmestelederrelasjonCommand
import no.nav.syfo.narmestelederrelasjon.application.RevokeActiveNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.application.RevokeActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.observability.countRevokeActiveNarmestelederrelasjon
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.api.tryReceive
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val REVOKE_ACTIVE_NARMESTELEDERRELASJON_PATH = "/linemanager/revoke"

fun Route.registerRevokeActiveNarmestelederrelasjonApi(
    revoke: RevokeActiveNarmestelederrelasjonUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(REVOKE_ACTIVE_NARMESTELEDERRELASJON_PATH) {
        method(HttpMethod.Post) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }
            handle {
                val principal = call.getMyPrincipal()
                val request = call.tryReceive<LinemanagerRevoke>()
                val result = revoke.execute(
                    RevokeActiveNarmestelederrelasjonCommand(
                        accessSubject = principal.toOrganizationAccessSubject(),
                        employeeIdent = PersonIdent(request.employeeIdentificationNumber.value),
                        organizationNumber = OrganizationNumber(request.orgNumber.value),
                        employeeLastName = request.lastName,
                    ),
                )
                countRevokeActiveNarmestelederrelasjon(result)
                call.respond(result.toHttpStatusOrThrow())
            }
        }
    }
}

internal fun RevokeActiveNarmestelederrelasjonResult.toHttpStatusOrThrow(): HttpStatusCode = when (this) {
    is RevokeActiveNarmestelederrelasjonResult.Revoked -> HttpStatusCode.Accepted
    RevokeActiveNarmestelederrelasjonResult.NoActiveRelation -> HttpStatusCode.NoContent
    RevokeActiveNarmestelederrelasjonResult.EmployeeNotFound ->
        throw ApiErrorException.BadRequestException("Could not find person in PDL")
    RevokeActiveNarmestelederrelasjonResult.EmployeeNameMismatch ->
        throw ApiErrorException.BadRequestException(
            "Last name for employee on sick leave does not correspond with registered value for the given national identification number",
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        )
    is RevokeActiveNarmestelederrelasjonResult.AccessDenied -> throw reason.toForbiddenException(organizationNumber)
}
