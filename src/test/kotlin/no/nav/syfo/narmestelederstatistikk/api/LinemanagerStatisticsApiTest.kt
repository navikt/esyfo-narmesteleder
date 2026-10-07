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
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
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
import no.nav.syfo.platform.api.internalApiV1
import no.nav.syfo.platform.api.testApiApplication
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
                    val response = client.get("$STATISTICS_PATH?orgNumber=$ORGANIZATION_NUMBER") {
                        bearerAuth(fixture.token())
                    }
                    response.status shouldBe HttpStatusCode.OK
                    mapper.readTree(response.bodyAsText()) shouldBe mapper.readTree(
                        """{"employeesOnSickLeaveWithoutLinemanager":1,"employeesOnSickLeaveWithLinemanager":2,"employeesNotOnSickLeaveWithLinemanager":3}""",
                    )
                    fixture.effects shouldBe listOf("access", "count")
                    fixture.organizations shouldBe listOf(OrganizationNumber(ORGANIZATION_NUMBER))
                    fixture.evaluations.single().second shouldBe OrganizationNumber(ORGANIZATION_NUMBER)
                    if (user) {
                        val subject = fixture.evaluations.single().first as OrganizationAccessSubject.PersonnelManager
                        subject.personIdent shouldBe PersonIdent(EMPLOYEE_IDENT)
                        (subject.accessToken.value() == fixture.token()) shouldBe true
                    } else {
                        fixture.evaluations.single().first shouldBe OrganizationAccessSubject.LpsSystemUser("some-user-id", OrganizationNumber(ORGANIZATION_NUMBER))
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
                    val response = client.get("$STATISTICS_PATH$query") { bearerAuth(fixture.token()) }
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
            Triple(DenialReason.MISSING_ORGANIZATION_ACCESS, ErrorType.MISSING_ORG_ACCESS, "User lacks access to organization: $ORGANIZATION_NUMBER"),
            Triple(DenialReason.MISSING_RESOURCE_ACCESS, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "User lacks access to required Altinn resource for organization: $ORGANIZATION_NUMBER"),
            Triple(DenialReason.SYSTEM_USER_REJECTED, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "System user does not have access to nav_syfo_oppgi-narmesteleder resource"),
        ).forEach { (reason, type, message) ->
            test("returns 403 for $reason without counting") {
                val fixture = StatisticsApiFixture(denialReason = reason)
                val before = requestCount("system")
                fixture.withApplication {
                    val response = client.get("$STATISTICS_PATH?orgNumber=$ORGANIZATION_NUMBER") { bearerAuth(fixture.token()) }
                    response.status shouldBe HttpStatusCode.Forbidden
                    val error = mapper.readTree(response.bodyAsText())
                    error.path("type").asText() shouldBe type.name
                    error.path("message").asText() shouldBe message
                    fixture.effects shouldBe listOf("access")
                    fixture.organizations shouldBe emptyList()
                    requestCount("system") shouldBe before
                }
                reason.toForbiddenException(OrganizationNumber(ORGANIZATION_NUMBER)).isAlreadyLogged shouldBe (reason == DenialReason.SYSTEM_USER_REJECTED)
            }
        }

        test("rejects an inactive token before checking access or counting") {
            val fixture = StatisticsApiFixture(activeToken = false)
            val before = requestCount("system")
            fixture.withApplication {
                client.get("$STATISTICS_PATH?orgNumber=$ORGANIZATION_NUMBER") {
                    bearerAuth(fixture.token())
                }.status shouldBe HttpStatusCode.Unauthorized
                fixture.effects shouldBe emptyList()
                requestCount("system") shouldBe before
            }
        }
    })

private const val ORGANIZATION_NUMBER = "910000001"
private const val EMPLOYEE_IDENT = "12345678901"
private const val TOKENX_ISSUER = "https://tokenx.nav.no"
private const val STATISTICS_PATH = "$INTERNAL_API_V1_PATH$LINEMANAGER_STATISTICS_API_PATH"
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
        if (user) EMPLOYEE_IDENT else ORGANIZATION_NUMBER,
        issuer = if (user) TOKENX_ISSUER else "https://test.maskinporten.no",
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
            pid = if (user) EMPLOYEE_IDENT else null,
            acr = if (user) "Level4" else null,
            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$ORGANIZATION_NUMBER"),
            scope = MASKINPORTEN_NL_SCOPE,
        )
        if (!activeToken) {
            coEvery { texas.introspectToken(any(), any()) } returns TexasIntrospectionResponse(active = false)
        }
    }

    fun token(): String = bearerToken

    fun withApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApiApplication(
            routes = { internalApiV1 { registerLinemanagerStatisticsApi(useCase, texas) } },
            block = block,
        )
    }
}

private fun requestCount(principalType: String): Double = METRICS_REGISTRY
    .find(LINEMANAGER_STATISTICS_TOTAL)
    .tag("principal_type", principalType)
    .counter()
    ?.count() ?: 0.0
