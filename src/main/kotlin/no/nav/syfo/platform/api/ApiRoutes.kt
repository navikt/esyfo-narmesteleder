package no.nav.syfo.platform.api

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationModulesLoaded
import io.ktor.server.application.pluginOrNull
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.application
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey

fun Route.apiV1(build: Route.() -> Unit): Route = authenticatedApiRoute(API_V1_PATH, build)

fun Route.internalApiV1(build: Route.() -> Unit): Route = authenticatedApiRoute(INTERNAL_API_V1_PATH, build)

private val AUTHENTICATED_API_ROUTE = AttributeKey<Unit>("AuthenticatedApiRoute")
private val AUTHENTICATION_GUARD_INSTALLED = AttributeKey<Unit>("ApiRouteAuthenticationGuardInstalled")

private fun Route.authenticatedApiRoute(path: String, build: Route.() -> Unit): Route = route(path) {
    attributes.put(AUTHENTICATED_API_ROUTE, Unit)
    application.requireAuthenticatedApiRoutesOnStartup()
    build()
}

// ApplicationModulesLoaded fires after every module has registered its routes, and a failure aborts startup.
private fun Application.requireAuthenticatedApiRoutesOnStartup() {
    if (attributes.contains(AUTHENTICATION_GUARD_INSTALLED)) return
    attributes.put(AUTHENTICATION_GUARD_INSTALLED, Unit)
    monitor.subscribe(ApplicationModulesLoaded) { it.requireAuthenticatedApiRoutes() }
}

private fun Application.requireAuthenticatedApiRoutes() {
    val unauthenticated = pluginOrNull(RoutingRoot)?.getAllRoutes().orEmpty().filter { endpoint ->
        val ancestors = generateSequence<Route>(endpoint) { it.parent }.toList()
        ancestors.any { it.attributes.contains(AUTHENTICATED_API_ROUTE) } &&
            ancestors.none { it.selector is AuthenticationRouteSelector }
    }
    check(unauthenticated.isEmpty()) {
        "Routes under $API_V1_PATH and $INTERNAL_API_V1_PATH must be authenticated: ${unauthenticated.joinToString()}"
    }
}
