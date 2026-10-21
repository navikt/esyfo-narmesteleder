package no.nav.syfo.narmestelederbehov

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.NAV_CALL_ID_HEADER
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.texas.TexasEnvironment
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederbehov.api.NARMESTELEDERBEHOV_PATH
import no.nav.syfo.narmestelederbehov.infrastructure.UpdateDialogTask
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.plugins.platformModule
import no.nav.syfo.texas.client.TexasHttpClient
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import org.koin.ktor.ext.get
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NarmestelederbehovModuleTest :
    FunSpec({
        test("registers its dependencies and mounts behov routes only under the public API prefix") {
            val environment = mockk<Environment> {
                every { texas } returns TexasEnvironment.createForLocal()
                every { otherProperties.persistLeesahNlBehov } returns false
                every { otherProperties.publicIngressUrl } returns "https://api.example.invalid"
                every { otherProperties.frontendBaseUrl } returns "https://frontend.example.invalid"
                every { otherProperties.updateDialogportenTaskProperties.pollingDelay } returns "7m"
            }
            val externalDependencies = module {
                single { ApplicationState() }
                single<DatabaseInterface> { UnusedDatabase }
                single<Database> { mockk() }
                single { environment }
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
                    narmestelederbehovModule()
                    get<UpdateDialogTask>() shouldNotBe null
                }

                val itemPath = "$NARMESTELEDERBEHOV_PATH/00000000-0000-0000-0000-000000000001"
                listOf(
                    HttpMethod.Get to itemPath,
                    HttpMethod.Put to itemPath,
                    HttpMethod.Get to NARMESTELEDERBEHOV_PATH,
                ).forEach { (httpMethod, path) ->
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
