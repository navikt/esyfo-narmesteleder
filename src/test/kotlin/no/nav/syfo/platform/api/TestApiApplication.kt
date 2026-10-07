package no.nav.syfo.platform.api

import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.syfo.application.api.installApiPlugins

/**
 * Starts the same request-handling plugins as production, without Koin, so route tests
 * only provide their routes and test doubles.
 */
fun testApiApplication(
    routes: Routing.() -> Unit,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        installApiPlugins()
        routing(routes)
    }
    block()
}
