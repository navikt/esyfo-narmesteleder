package no.nav.syfo.platform.api

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationModulesLoaded
import io.ktor.server.application.pluginOrNull
import io.ktor.server.auth.AuthenticateProvidersKey
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.application
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI

fun Route.apiV1(build: Route.() -> Unit): Route = authenticatedApiRoute(API_V1_PATH, build)

fun Route.internalApiV1(build: Route.() -> Unit): Route = authenticatedApiRoute(INTERNAL_API_V1_PATH, build)

/** Marks routes that are intentionally reachable without authentication, such as probes and API documentation. */
fun Route.publicRoutes(build: Route.() -> Unit): Route = createChild(PublicRouteSelector).apply(build)

// ApplicationModulesLoaded fires after every module has registered its routes, and a failure aborts startup.
fun Application.requireAuthenticatedRoutesOnStartup() {
    if (attributes.contains(AUTHENTICATION_GUARD_INSTALLED)) return
    attributes.put(AUTHENTICATION_GUARD_INSTALLED, Unit)
    monitor.subscribe(ApplicationModulesLoaded) { it.requireAuthenticatedRoutes() }
}

private object PublicRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(public)"
}

private val AUTHENTICATED_API_ROUTE = AttributeKey<Unit>("AuthenticatedApiRoute")
private val AUTHENTICATION_GUARD_INSTALLED = AttributeKey<Unit>("RouteAuthenticationGuardInstalled")

private fun Route.authenticatedApiRoute(path: String, build: Route.() -> Unit): Route = route(path) {
    attributes.put(AUTHENTICATED_API_ROUTE, Unit)
    application.requireAuthenticatedRoutesOnStartup()
    build()
}

// The guard cannot see skipWhen predicates on providers; do not configure skipWhen for Texas providers.
private fun Application.requireAuthenticatedRoutes() {
    val unauthenticated = pluginOrNull(RoutingRoot)?.getAllRoutes().orEmpty().filterNot { endpoint ->
        val ancestors = generateSequence<Route>(endpoint) { it.parent }.toList()
        ancestors.requireAuthentication() || ancestors.isPublic()
    }
    check(unauthenticated.isEmpty()) {
        "Routes must require authentication or be declared in publicRoutes: ${unauthenticated.joinToString()}"
    }
}

private fun List<Route>.isPublic(): Boolean = any { it.selector === PublicRouteSelector } &&
    none { it.attributes.contains(AUTHENTICATED_API_ROUTE) }

@OptIn(InternalAPI::class)
private fun List<Route>.requireAuthentication(): Boolean = any { it.selector is AuthenticationRouteSelector } &&
    none { it.attributes.getOrNull(AuthenticateProvidersKey)?.strategy == AuthenticationStrategy.Optional }
