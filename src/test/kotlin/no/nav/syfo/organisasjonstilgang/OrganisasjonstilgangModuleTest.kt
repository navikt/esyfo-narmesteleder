package no.nav.syfo.organisasjonstilgang

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import no.nav.syfo.organisasjonstilgang.api.ACCESSIBLE_ORGANIZATIONS_API_PATH
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerClient
import no.nav.syfo.platform.api.API_V1_PATH
import no.nav.syfo.platform.api.INTERNAL_API_V1_PATH
import no.nav.syfo.platform.api.NAV_CALL_ID_HEADER
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.database.DatabaseInterface
import no.nav.syfo.platform.observability.ApplicationState
import no.nav.syfo.plugins.platformModule
import org.koin.dsl.module
import java.sql.Connection

class OrganisasjonstilgangModuleTest :
    FunSpec({
        test("registers its dependencies and mounts accessible organizations only under the public API prefix") {
            val externalDependencies = module {
                single { ApplicationState() }
                single<DatabaseInterface> { UnusedDatabase }
                single<TexasHttpClient> { mockk() }
                single<AltinnTilgangerClient> { mockk() }
            }

            testApplication {
                application {
                    platformModule(listOf(externalDependencies))
                    organisasjonstilgangModule()
                }

                // Missing tokens are rejected before clients or repositories are used.
                val response = client.get("$API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH")
                response.status shouldBe HttpStatusCode.Unauthorized
                response.headers[NAV_CALL_ID_HEADER] shouldNotBe null

                client.get("$INTERNAL_API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH").status shouldBe HttpStatusCode.NotFound
            }
        }
    })

private object UnusedDatabase : DatabaseInterface {
    override val connection: Connection
        get() = error("Database is not used by this test")
}
