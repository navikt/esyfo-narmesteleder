package no.nav.syfo.platform.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/**
 * Starts the same request-handling plugins as production, with Koin holding only the given
 * [texasHttpClient], so route tests only provide their routes and test doubles.
 */
fun testApiApplication(
    texasHttpClient: TexasHttpClient,
    routes: Routing.() -> Unit,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = startTestApplication(
    plugins = {
        install(Koin) { modules(module { single { texasHttpClient } }) }
        installApiPlugins()
    },
    routes = routes,
    block = block,
)

/** Like [testApiApplication], but without the call-id plugin: only content negotiation and status pages. */
fun testRouteApplication(
    texasHttpClient: TexasHttpClient,
    routes: Routing.() -> Unit,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = startTestApplication(
    plugins = {
        install(Koin) { modules(module { single { texasHttpClient } }) }
        installContentNegotiation()
        installStatusPages()
    },
    routes = routes,
    block = block,
)

/** A test client that (de)serializes JSON bodies with the same Jackson settings as the API. */
fun ApplicationTestBuilder.createJsonClient(): HttpClient = createClient {
    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }
}

private fun startTestApplication(
    plugins: Application.() -> Unit,
    routes: Routing.() -> Unit,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        plugins()
        routing(routes)
    }
    block()
}
