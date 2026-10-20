package no.nav.syfo.narmestelederrelasjon

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.post
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
import no.nav.syfo.application.valkey.ValkeyCache
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederrelasjon.api.LINE_MANAGER_LOOKUP_PATH
import no.nav.syfo.narmestelederrelasjon.api.SUBMIT_NARMESTELEDERRELASJON_PATH
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.plugins.platformModule
import no.nav.syfo.texas.client.TexasHttpClient
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import java.sql.Connection

class NarmestelederrelasjonModuleTest :
    FunSpec({
        test("registers its dependencies and mounts relasjon routes on their own API prefixes") {
            val environment = mockk<Environment> {
                every { texas } returns TexasEnvironment.createForLocal()
            }
            val externalDependencies = module {
                single { ApplicationState() }
                single<DatabaseInterface> { UnusedDatabase }
                single<Database> { mockk() }
                single { environment }
                single<TexasHttpClient> { mockk() }
                single<OrganizationAccess> { mockk() }
                single<AaregClient> { mockk() }
                single<DinesykmeldteClient> { mockk() }
                single<EregClient> { mockk() }
                single<PdlClient> { mockk() }
                single<ValkeyCache> { mockk() }
                single<SykmeldingNarmestelederProducer> { mockk() }
            }

            testApplication {
                application {
                    platformModule(listOf(externalDependencies))
                    narmestelederrelasjonModule()
                }

                // Missing tokens are rejected before clients or repositories are used.
                val publicResponse = client.post("$API_V1_PATH$SUBMIT_NARMESTELEDERRELASJON_PATH")
                publicResponse.status shouldBe HttpStatusCode.Unauthorized
                publicResponse.headers[NAV_CALL_ID_HEADER] shouldNotBe null

                val internalResponse = client.post("$INTERNAL_API_V1_PATH$LINE_MANAGER_LOOKUP_PATH")
                internalResponse.status shouldBe HttpStatusCode.Unauthorized
                internalResponse.headers[NAV_CALL_ID_HEADER] shouldNotBe null

                client.post("$INTERNAL_API_V1_PATH$SUBMIT_NARMESTELEDERRELASJON_PATH").status shouldBe HttpStatusCode.NotFound
                client.post("$API_V1_PATH$LINE_MANAGER_LOOKUP_PATH").status shouldBe HttpStatusCode.NotFound
            }
        }
    })

private object UnusedDatabase : DatabaseInterface {
    override val connection: Connection
        get() = error("Database is not used by this test")
}
