package no.nav.syfo.narmestelederstatistikk.api

import DefaultOrganization
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import createMockToken
import defaultMocks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederstatistikk.application.GetNarmestelederstatistikkUseCase
import no.nav.syfo.narmestelederstatistikk.application.Narmestelederstatistikk
import no.nav.syfo.narmestelederstatistikk.application.NarmestelederstatistikkRepository
import no.nav.syfo.narmestelederstatistikk.observability.LINEMANAGER_STATISTICS_TOTAL
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse

class LinemanagerStatisticsApiTest :
    FunSpec({
        listOf(false, true).forEach { user ->
            test("returns exact statistics JSON and counts successful ${if (user) "user" else "system"} requests") {
                val fixture = StatisticsApiFixture(user = user)
                val principalType = if (user) "user" else "system"
                val before = requestCount(principalType)
                fixture.withApplication {
                    val response = client.get("$statisticsPath?orgNumber=$organizationNumber") {
                        bearerAuth(fixture.token())
                    }
                    response.status shouldBe HttpStatusCode.OK
                    mapper.readTree(response.bodyAsText()) shouldBe mapper.readTree(
                        """{"employeesOnSickLeaveWithoutLinemanager":1,"employeesOnSickLeaveWithLinemanager":2,"employeesNotOnSickLeaveWithLinemanager":3}""",
                    )
                    fixture.effects shouldBe listOf("access", "count")
                    fixture.organizations shouldBe listOf(OrganizationNumber(organizationNumber))
                    fixture.evaluations.single().second shouldBe OrganizationNumber(organizationNumber)
                    if (user) {
                        val subject = fixture.evaluations.single().first as OrganizationAccessSubject.PersonnelManager
                        subject.personIdent shouldBe PersonIdent(employeeIdent)
                        (subject.accessToken.value() == fixture.token()) shouldBe true
                    } else {
                        fixture.evaluations.single().first shouldBe OrganizationAccessSubject.LpsSystemUser("some-user-id", OrganizationNumber(organizationNumber))
                    }
                    requestCount(principalType) shouldBe before + 1
                }
            }
        }

        listOf(
            Triple("", ErrorType.BAD_REQUEST, "Missing orgNumber parameter"),
            Triple("?orgNumber=invalid", ErrorType.INVALID_FORMAT, "OrganizationNumber must be exactly 9 digits"),
            Triple("?orgNumber=", ErrorType.INVALID_FORMAT, "OrganizationNumber must be exactly 9 digits"),
        ).forEach { (query, type, message) ->
            test("returns 400 for '$query' without checking access or counting") {
                val fixture = StatisticsApiFixture()
                val before = requestCount("system")
                fixture.withApplication {
                    val response = client.get("$statisticsPath$query") { bearerAuth(fixture.token()) }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val error = mapper.readTree(response.bodyAsText())
                    error.path("type").asText() shouldBe type.name
                    error.path("message").asText() shouldBe message
                    fixture.effects shouldBe emptyList()
                    requestCount("system") shouldBe before
                }
            }
        }

        listOf(
            Triple(DenialReason.MISSING_ORGANIZATION_ACCESS, ErrorType.MISSING_ORG_ACCESS, "User lacks access to organization: $organizationNumber"),
            Triple(DenialReason.MISSING_RESOURCE_ACCESS, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "User lacks access to required Altinn resource for organization: $organizationNumber"),
            Triple(DenialReason.SYSTEM_USER_REJECTED, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "System user does not have access to nav_syfo_oppgi-narmesteleder resource"),
        ).forEach { (reason, type, message) ->
            test("returns 403 for $reason without counting") {
                val fixture = StatisticsApiFixture(denialReason = reason)
                val before = requestCount("system")
                fixture.withApplication {
                    val response = client.get("$statisticsPath?orgNumber=$organizationNumber") { bearerAuth(fixture.token()) }
                    response.status shouldBe HttpStatusCode.Forbidden
                    val error = mapper.readTree(response.bodyAsText())
                    error.path("type").asText() shouldBe type.name
                    error.path("message").asText() shouldBe message
                    fixture.effects shouldBe listOf("access")
                    fixture.organizations shouldBe emptyList()
                    requestCount("system") shouldBe before
                }
                reason.toForbiddenException(OrganizationNumber(organizationNumber)).isAlreadyLogged shouldBe (reason == DenialReason.SYSTEM_USER_REJECTED)
            }
        }

        test("is not available through the external API") {
            val fixture = StatisticsApiFixture()
            fixture.withApplication {
                client.get("$API_V1_PATH$LINEMANAGER_STATISTICS_API_PATH?orgNumber=$organizationNumber") {
                    bearerAuth(fixture.token())
                }.status shouldBe HttpStatusCode.NotFound
                fixture.effects shouldBe emptyList()
            }
        }

        test("rejects an inactive token before checking access or counting") {
            val fixture = StatisticsApiFixture(activeToken = false)
            val before = requestCount("system")
            fixture.withApplication {
                client.get("$statisticsPath?orgNumber=$organizationNumber") {
                    bearerAuth(fixture.token())
                }.status shouldBe HttpStatusCode.Unauthorized
                fixture.effects shouldBe emptyList()
                requestCount("system") shouldBe before
            }
        }
    })

private const val organizationNumber = "910000001"
private const val employeeIdent = "12345678901"
private const val tokenXIssuer = "https://tokenx.nav.no"
private const val statisticsPath = "$INTERNAL_API_V1_PATH$LINEMANAGER_STATISTICS_API_PATH"
private val mapper = jacksonObjectMapper()

private class StatisticsApiFixture(
    private val user: Boolean = false,
    denialReason: DenialReason? = null,
    activeToken: Boolean = true,
) {
    val effects = mutableListOf<String>()
    val organizations = mutableListOf<OrganizationNumber>()
    val evaluations = mutableListOf<Pair<OrganizationAccessSubject, OrganizationNumber>>()
    private val texas = mockk<TexasHttpClient>()
    private val bearerToken = createMockToken(
        if (user) employeeIdent else organizationNumber,
        issuer = if (user) tokenXIssuer else "https://test.maskinporten.no",
    )
    private val useCase = GetNarmestelederstatistikkUseCase(
        organizationAccess = OrganizationAccess { subject, organization ->
            effects += "access"
            evaluations += subject to organization
            denialReason?.let { OrganizationAccessResult.Denied(it) } ?: OrganizationAccessResult.Granted(null)
        },
        repository = NarmestelederstatistikkRepository { organization ->
            effects += "count"
            organizations += organization
            Narmestelederstatistikk(1, 2, 3)
        },
    )

    init {
        texas.defaultMocks(
            pid = if (user) employeeIdent else null,
            acr = if (user) "Level4" else null,
            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$organizationNumber"),
            scope = MASKINPORTEN_NL_SCOPE,
        )
        if (!activeToken) {
            coEvery { texas.introspectToken(any(), any()) } returns TexasIntrospectionResponse(active = false)
        }
    }

    fun token(): String = bearerToken

    fun withApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                installContentNegotiation()
                installStatusPages()
                routing {
                    route(INTERNAL_API_V1_PATH) {
                        install(AddTokenIssuerPlugin)
                        registerLinemanagerStatisticsApi(useCase, texas)
                    }
                }
            }
            block()
        }
    }
}

private fun requestCount(principalType: String): Double = METRICS_REGISTRY
    .find(LINEMANAGER_STATISTICS_TOTAL)
    .tag("principal_type", principalType)
    .counter()
    ?.count() ?: 0.0
