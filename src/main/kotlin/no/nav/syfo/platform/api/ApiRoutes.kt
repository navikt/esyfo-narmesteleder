package no.nav.syfo.platform.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.auth.AddTokenIssuerPlugin

private val TOKEN_ISSUER_INSTALLED = AttributeKey<Unit>("TokenIssuerInstalled")

fun Route.apiV1(build: Route.() -> Unit): Route = tokenIssuerRoute(API_V1_PATH, build)

fun Route.internalApiV1(build: Route.() -> Unit): Route = tokenIssuerRoute(INTERNAL_API_V1_PATH, build)

// Ktor reuses the route node for the same path, and installing a plugin twice on it fails.
private fun Route.tokenIssuerRoute(path: String, build: Route.() -> Unit): Route = route(path) {
    if (!attributes.contains(TOKEN_ISSUER_INSTALLED)) {
        install(AddTokenIssuerPlugin)
        attributes.put(TOKEN_ISSUER_INSTALLED, Unit)
    }
    build()
}
