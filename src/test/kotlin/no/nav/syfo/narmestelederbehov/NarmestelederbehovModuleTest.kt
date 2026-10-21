package no.nav.syfo.narmestelederbehov

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import no.nav.syfo.bootstrap.platformModule
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederbehov.api.NARMESTELEDERBEHOV_PATH
import no.nav.syfo.narmestelederbehov.infrastructure.UpdateDialogTask
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjon
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.platform.api.API_V1_PATH
import no.nav.syfo.platform.api.INTERNAL_API_V1_PATH
import no.nav.syfo.platform.api.NAV_CALL_ID_HEADER
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.database.DatabaseInterface
import no.nav.syfo.platform.observability.ApplicationState
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import org.koin.ktor.ext.get
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

class NarmestelederbehovModuleTest :
    FunSpec({
        val itemPath = "$NARMESTELEDERBEHOV_PATH/00000000-0000-0000-0000-000000000001"
        listOf(
            Triple("GET item", HttpMethod.Get, itemPath),
            Triple("PUT item", HttpMethod.Put, itemPath),
            Triple("GET list", HttpMethod.Get, NARMESTELEDERBEHOV_PATH),
        ).forEach { (route, httpMethod, path) ->
            test("registers its dependencies and mounts the behov $route route only under the public API prefix") {
                val settings = NarmestelederbehovSettings(
                    persistLeesahNlBehov = false,
                    publicIngressUrl = "https://api.example.invalid",
                    frontendBaseUrl = "https://frontend.example.invalid",
                    dialogUpdatePollingInterval = 7.minutes,
                )
                val externalDependencies = module {
                    single { ApplicationState() }
                    single<DatabaseInterface> { UnusedDatabase }
                    single<Database> { mockk() }
                    single { Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC) }
                    single<TexasHttpClient> { mockk() }
                    single<OrganizationAccess> { mockk() }
                    single<PdlClient> { mockk() }
                    single<DialogportenClient> { mockk() }
                    single<EstablishNarmestelederrelasjon> { mockk() }
                }

                testApplication {
                    application {
                        platformModule(listOf(externalDependencies))
                        narmestelederbehovModule(settings)
                        get<UpdateDialogTask>() shouldNotBe null
                    }

                    // Missing tokens are rejected before clients or repositories are used.
                    val publicResponse = client.request("$API_V1_PATH$path") {
                        method = httpMethod
                    }
                    publicResponse.status shouldBe HttpStatusCode.Unauthorized
                    publicResponse.headers[NAV_CALL_ID_HEADER] shouldNotBe null

                    client.request("$INTERNAL_API_V1_PATH$path") {
                        method = httpMethod
                    }.status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    })

private object UnusedDatabase : DatabaseInterface {
    override val connection: Connection
        get() = error("Database is not used by this test")
}
