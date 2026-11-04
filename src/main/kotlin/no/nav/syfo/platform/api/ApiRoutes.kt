package no.nav.syfo.platform.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.route

fun Route.apiV1(build: Route.() -> Unit): Route = route(API_V1_PATH, build)

fun Route.internalApiV1(build: Route.() -> Unit): Route = route(INTERNAL_API_V1_PATH, build)
