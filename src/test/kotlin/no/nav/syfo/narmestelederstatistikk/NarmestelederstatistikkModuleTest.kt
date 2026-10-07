package no.nav.syfo.narmestelederstatistikk

import DefaultOrganization
import createMockToken
import defaultMocks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.NAV_CALL_ID_HEADER
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.narmestelederstatistikk.api.LINEMANAGER_STATISTICS_API_PATH
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.plugins.platformModule
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import java.sql.Connection

class NarmestelederstatistikkModuleTest :
    FunSpec({
        test("registers its dependencies and mounts statistics on the internal API only") {
            val texas = mockk<TexasHttpClient>().apply {
                defaultMocks(
                    systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$ORGANIZATION_NUMBER"),
                    scope = MASKINPORTEN_NL_SCOPE,
                )
            }
            val externalDependencies = module {
                single { ApplicationState() }
                single<DatabaseInterface> { UnusedDatabase }
                single<Database> { mockk() }
                single { texas }
                single { OrganizationAccess { _, _ -> OrganizationAccessResult.Granted(null) } }
            }
            val token = createMockToken(ORGANIZATION_NUMBER, issuer = "https://test.maskinporten.no")

            testApplication {
                application {
                    platformModule(listOf(externalDependencies))
                    narmestelederstatistikkModule()
                }

                // Missing orgNumber is rejected before the repository is used, so no database is needed.
                val response = client.get("$INTERNAL_API_V1_PATH$LINEMANAGER_STATISTICS_API_PATH") { bearerAuth(token) }
                response.status shouldBe HttpStatusCode.BadRequest
                response.headers[NAV_CALL_ID_HEADER] shouldNotBe null

                client.get("$API_V1_PATH$LINEMANAGER_STATISTICS_API_PATH") { bearerAuth(token) }.status shouldBe HttpStatusCode.NotFound
            }
        }
    })

private const val ORGANIZATION_NUMBER = "910000001"

private object UnusedDatabase : DatabaseInterface {
    override val connection: Connection
        get() = error("Database is not used by this test")
}
