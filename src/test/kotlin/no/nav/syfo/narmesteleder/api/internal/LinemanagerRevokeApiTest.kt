package no.nav.syfo.narmesteleder.api.internal

import DefaultOrganization
import createMockToken
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.narmesteleder.db.INarmestelederRevokeDb
import no.nav.syfo.narmesteleder.db.RevokableNarmestelederEntity
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.EmployeeLinemanagerService
import no.nav.syfo.narmesteleder.service.LinemanagerRevokeService
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.narmesteleder.service.LinemanagerStatisticsService
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.AuthorizationDetail
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.util.UUID

private const val TOKEN_X_ISSUER = "https://tokenx.example.com"
private const val MASKINPORTEN_ISSUER = "https://test.maskinporten.no"

class LinemanagerRevokeApiTest :
    DescribeSpec({
        val texasHttpClient = mockk<TexasHttpClient>()
        val revokeDb = mockk<INarmestelederRevokeDb>()
        val kafkaProducer = mockk<ISykmeldingNLKafkaProducer>(relaxed = true)
        val revokeService = LinemanagerRevokeService(revokeDb, NarmestelederKafkaService(kafkaProducer))

        val narmestelederId = UUID.randomUUID()
        val employee = PersonalIdentificationNumber("12345678901")
        val manager = PersonalIdentificationNumber("10987654321")
        val orgNumber = OrganizationNumber("123456789")

        fun relation(isActive: Boolean = true) = RevokableNarmestelederEntity(
            narmestelederId = narmestelederId,
            employeeIdentificationNumber = employee,
            managerIdentificationNumber = manager,
            orgNumber = orgNumber,
            isActive = isActive,
        )

        fun withTestApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        registerInternalApi(
                            texasHttpClient = texasHttpClient,
                            narmestelederLookupService = mockk<NarmestelederLookupService>(),
                            preAuthorizedApps = setOf("calling-app-id"),
                            linemanagerSearchService = mockk<LinemanagerSearchService>(),
                            linemanagerStatisticsService = mockk<LinemanagerStatisticsService>(),
                            employeeLinemanagerService = mockk<EmployeeLinemanagerService>(),
                            linemanagerRevokeService = revokeService,
                        )
                    }
                }
                test()
            }
        }

        fun tokenXToken(pid: String) = createMockToken(pid, issuer = TOKEN_X_ISSUER)

        beforeTest {
            clearMocks(texasHttpClient, revokeDb, kafkaProducer)
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } answers {
                TexasIntrospectionResponse(
                    active = true,
                    acr = "Level4",
                    pid = employee.value,
                )
            }
        }

        describe("DELETE /internal/api/v1/linemanager/{id}") {
            it("accepts the request and publishes ARBEIDSTAGER_REVOKE when the employee revokes") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()

                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/$narmestelederId") {
                        bearerAuth(tokenXToken(employee.value))
                    }

                    response.status shouldBe HttpStatusCode.Accepted
                }
                verify(exactly = 1) {
                    kafkaProducer.sendSykmldingNLBrudd(
                        match { it.sykmeldtFnr == employee.value && it.orgnummer == orgNumber.value },
                        NlResponseSource.ARBEIDSTAGER_REVOKE,
                    )
                }
            }

            it("accepts the request and publishes NARMESTELEDER_REVOKE when the line manager revokes") {
                coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns TexasIntrospectionResponse(
                    active = true,
                    acr = "Level4",
                    pid = manager.value,
                )
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()

                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/$narmestelederId") {
                        bearerAuth(tokenXToken(manager.value))
                    }

                    response.status shouldBe HttpStatusCode.Accepted
                }
                verify(exactly = 1) {
                    kafkaProducer.sendSykmldingNLBrudd(
                        match { it.sykmeldtFnr == employee.value && it.orgnummer == orgNumber.value },
                        NlResponseSource.NARMESTELEDER_REVOKE,
                    )
                }
            }

            it("accepts the request without republishing when the relation is already revoked") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation(isActive = false)

                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/$narmestelederId") {
                        bearerAuth(tokenXToken(employee.value))
                    }

                    response.status shouldBe HttpStatusCode.Accepted
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("returns 404 when the relation does not exist") {
                coEvery { revokeDb.findByNarmestelederId(any()) } returns null

                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/${UUID.randomUUID()}") {
                        bearerAuth(tokenXToken(employee.value))
                    }

                    response.status shouldBe HttpStatusCode.NotFound
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("returns 404 when the logged in person is not part of the relation") {
                coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns TexasIntrospectionResponse(
                    active = true,
                    acr = "Level4",
                    pid = "11111111111",
                )
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()

                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/$narmestelederId") {
                        bearerAuth(tokenXToken("11111111111"))
                    }

                    response.status shouldBe HttpStatusCode.NotFound
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("returns 400 when the id is not a valid UUID") {
                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/not-a-uuid") {
                        bearerAuth(tokenXToken(employee.value))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("does not resolve the sibling search route as an id") {
                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/search") {
                        bearerAuth(tokenXToken(employee.value))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }

            it("returns 401 when the request has no token") {
                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/$narmestelederId")

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("rejects a Maskinporten token") {
                coEvery { texasHttpClient.introspectToken("maskinporten", any()) } returns TexasIntrospectionResponse(
                    active = true,
                    scope = MASKINPORTEN_NL_SCOPE,
                    consumer = DefaultOrganization,
                    authorizationDetails = listOf(
                        AuthorizationDetail(
                            type = "urn:altinn:systemuser",
                            systemuserOrg = DefaultOrganization,
                            systemuserId = listOf("some-user-id"),
                            systemId = "some-system-id",
                        )
                    ),
                )
                coEvery { revokeDb.findByNarmestelederId(any()) } returns relation()

                withTestApplication {
                    val response = client.delete("/internal/api/v1/linemanager/$narmestelederId") {
                        bearerAuth(createMockToken("0192:123456789", issuer = MASKINPORTEN_ISSUER))
                    }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }
        }
    })
