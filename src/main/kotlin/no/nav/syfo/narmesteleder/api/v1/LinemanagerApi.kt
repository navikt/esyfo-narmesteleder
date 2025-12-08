package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authentication
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Instant
import java.time.format.DateTimeParseException
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exceptions.UnauthorizedException
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRequiremenCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.domain.PageInfo
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validateNarmesteLeder
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINEMANAGER_API_PATH = "/linemanager"
const val REVOKE_PATH = "$LINEMANAGER_API_PATH/revoke"
const val RECUIREMENT_PATH = "$LINEMANAGER_API_PATH/requirement"
fun Route.registerLinemanagerApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    validationService: ValidationService,
    texasHttpClient: TexasHttpClient,
    linemanagerRequirementRestHandler: LinemanagerRequirementRESTHandler,
) {
    route(LINEMANAGER_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        post() {
            val create = call.tryReceive<Linemanager>()
            val actors = validationService.validateLinemanager(create, call.getMyPrincipal())

            narmestelederKafkaService.sendNarmesteLederRelasjon(
                create,
                actors,
                NlResponseSource.getSourceFrom(call.getMyPrincipal(), create)
            )

            call.respond(HttpStatusCode.Accepted)
        }
    }

    route(REVOKE_PATH) {
        post() {
            val principal = call.getMyPrincipal()
            val revoke = call.tryReceive<LinemanagerRevoke>()
            val employee = validationService.validateLinemanagerRevoke(revoke, principal)

            val tweakedRevoke = revoke.copy(employeeIdentificationNumber = employee.nationalIdentificationNumber)
            narmestelederKafkaService.avbrytNarmesteLederRelation(
                tweakedRevoke,
                NlResponseSource.getSourceFrom(principal, tweakedRevoke)
            )

            call.respond(HttpStatusCode.Accepted)
        }
    }

    route(RECUIREMENT_PATH) {
        put("/{id}") {
            val id = call.getUUIDFromPathVariable(name = "id")
            val linemanager = call.tryReceive<Manager>()

            linemanagerRequirementRestHandler.handleUpdatedRequirement(
                linemanager,
                id,
                principal = call.getMyPrincipal()
            )

            call.respond(HttpStatusCode.Accepted)
        }

        get("/{id}") {
            val id = call.getUUIDFromPathVariable(name = "id")
            val nlBehov = linemanagerRequirementRestHandler.handleGetLinemanagerRequirement(
                requirementId = id,
                principal = call.getMyPrincipal()
            )
            call.respond(HttpStatusCode.OK, nlBehov)
        }

        get {
            val pageSize = call.getPageSize()
            val createAfter = call.getCreatedAfter()
            val orgNumber = call.getQueryParameter("orgNumber")
            val principal = call.getMyPrincipal()
            validationService.validateLinemanagerCollectionAccess(principal, orgNumber)
            val collection = linemanagerRequirementRestHandler.handleGetLinemanagerRequirementsCollection(
                pageSize = pageSize,
                createdAfter = createAfter,
                orgNumber = orgNumber,
                principal = principal,
            )
            call.respond(
                HttpStatusCode.OK, LinemanagerRequiremenCollection(
                    linemanagerRequirements = collection,
                    meta = PageInfo(
                        size = collection.size,
                        pageSize = pageSize,
                    )
                )
            )
        }
    }
}

fun RoutingCall.getCreatedAfter(): Instant {
    val createdAfter = getQueryParameter("createdAfter")
    try {
        return Instant.parse(createdAfter)
    } catch (e: DateTimeParseException) {
        throw ApiErrorException.BadRequestException(
            "Invalid date format for createdAfter parameter. Expected ISO-8601 format.",
            type = ErrorType.BAD_REQUEST
        )
    }
}

fun RoutingCall.getPageSize(): Int =
    this.queryParameters["pageSize"]?.let {
        it.toIntOrNull().let { pageSize ->
            when (it.toIntOrNull()) {
                in 1..LinemanagerRequiremenCollection.DEFAULT_PAGE_SIZE -> pageSize
                else -> LinemanagerRequiremenCollection.DEFAULT_PAGE_SIZE
            }
        }
    } ?: LinemanagerRequiremenCollection.DEFAULT_PAGE_SIZE

fun RoutingCall.getMyPrincipal(): Principal =
    when (attributes[TOKEN_ISSUER]) {
        JwtIssuer.MASKINPORTEN -> {
            authentication.principal<SystemPrincipal>() ?: throw UnauthorizedException()
        }

        JwtIssuer.TOKEN_X -> {
            authentication.principal<UserPrincipal>() ?: throw UnauthorizedException()
        }

        else -> throw UnauthorizedException()
    }
