package no.nav.syfo.organisasjonstilgang.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.integration.ereg.EregCache
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.ereg.Organisasjon
import no.nav.syfo.integration.ereg.RecordingEregClient
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AuthorizationDecision
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.User
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerService
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.FakeAltinnTilgangerClient
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import org.slf4j.MDC

class AltinnOrganizationAccessTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(
            systemUserAccessRejected,
            rejectionReasons = setOf(SYSTEM_USER_ACCESS_NOT_GRANTED),
        )
        val productionLogs = extension(ProductionLogs(AltinnOrganizationAccess::class.java))
        lateinit var capture: ProductionLogCapture
        val privacyCanaries = listOf(REQUESTED_ORG, SYSTEM_USER_ORG, SYSTEM_USER_ID, PERSON_IDENT, ACCESS_TOKEN)

        fun rejectionRecord(): JsonNode {
            contract.assertValid(capture.records, expectedCount = 1)
            return mapper.readTree(capture.records.single())
        }

        beforeTest {
            capture = productionLogs.capture()
        }
        afterTest {
            capture.use { capture ->
                capture.records.forEach { line -> privacyCanaries.forEach { line shouldNotContain it } }
            }
        }

        context("personnel manager") {
            listOf(
                UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.REQUEST, null, IllegalStateException("Synthetic failure")),
                UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("is_error"))),
                UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException()),
                UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 503, IllegalStateException()),
            ).forEach { upstreamFailure ->
                test("returns Unavailable without granting, denying or logging for ${upstreamFailure.upstream} ${upstreamFailure.stage} ${upstreamFailure.status}") {
                    val fixture = AccessFixture()
                    fixture.altinn.setFailure(upstreamFailure)
                    val result = fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG))
                    result shouldBe OrganizationAccessResult.Unavailable(upstreamFailure)
                    result.shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs upstreamFailure
                    fixture.pdp.requestedOrganizations shouldContainExactly emptyList()
                    capture.records.shouldBeEmpty()
                }
            }
            test("is granted with the narmesteleder resource for the organization") {
                val fixture = AccessFixture()
                fixture.altinn.addAccess(PERSON_IDENT, REQUESTED_ORG)
                val accessPolicy = fixture.altinn.accessPolicy.single()
                fixture.altinn.accessPolicy[0] = accessPolicy.copy(
                    altinnTilgangerResponse = accessPolicy.altinnTilgangerResponse.copy(
                        hierarki = accessPolicy.altinnTilgangerResponse.hierarki.map { it.copy(navn = "  Test Org  ") },
                    ),
                )

                fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Granted(organizationName = "Test Org")
            }

            test("is denied missing organization access without Altinn access to the organization and logs nothing") {
                val fixture = AccessFixture()

                fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)
                capture.records.shouldBeEmpty()
            }

            test("is denied missing resource access without the narmesteleder resource and logs nothing") {
                val fixture = AccessFixture()
                fixture.altinn.addAccess(PERSON_IDENT, REQUESTED_ORG, altinn3tilgang = "some-other-resource")

                fixture.access.evaluate(personnelManager(), OrganizationNumber(REQUESTED_ORG)) shouldBe
                    OrganizationAccessResult.Denied(DenialReason.MISSING_RESOURCE_ACCESS)
                capture.records.shouldBeEmpty()
            }
        }

        context("LPS system user") {
            val decisions = AuthorizationDecision.entries

            decisions.forEach { directDecision ->
                test("direct $directDecision outside the hierarchy never checks a fallback decision") {
                    val fixture = AccessFixture()
                    fixture.pdp.decisions[REQUESTED_ORG] = directDecision
                    fixture.pdp.permit(SYSTEM_USER_ORG)
                    fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(UNRELATED_ORG)

                    val result = fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

                    fixture.pdp.requestedOrganizations shouldContainExactly listOf(setOf(REQUESTED_ORG))
                    if (directDecision == AuthorizationDecision.Permit) {
                        result shouldBe OrganizationAccessResult.Granted(organizationName = null)
                        capture.records.shouldBeEmpty()
                    } else {
                        result shouldBe OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED)
                        rejectionRecord().shouldBeRejection(directDecision.name, fallbackDecision = "not_checked")
                    }
                }

                decisions.forEach { fallbackDecision ->
                    test("direct $directDecision and hierarchy $fallbackDecision keep the authorization outcome") {
                        val fixture = AccessFixture()
                        fixture.pdp.decisions[REQUESTED_ORG] = directDecision
                        fixture.pdp.decisions[SYSTEM_USER_ORG] = fallbackDecision
                        fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)

                        val result = fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

                        fixture.pdp.requestedOrganizations shouldContainExactly if (directDecision == AuthorizationDecision.Permit) {
                            listOf(setOf(REQUESTED_ORG))
                        } else {
                            listOf(setOf(REQUESTED_ORG), setOf(SYSTEM_USER_ORG))
                        }
                        if (directDecision == AuthorizationDecision.Permit || fallbackDecision == AuthorizationDecision.Permit) {
                            result shouldBe OrganizationAccessResult.Granted(organizationName = null)
                            capture.records.shouldBeEmpty()
                        } else {
                            result shouldBe OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED)
                            rejectionRecord().shouldBeRejection(directDecision.name, fallbackDecision.name)
                        }
                    }
                }
            }

            listOf(
                UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 400, errors = listOf(UpstreamError("invalid_grant"))),
                UpstreamFailure(UpstreamName("altinn-authentication"), UpstreamFailureStage.TOKEN_EXCHANGE, 200, IllegalArgumentException()),
                UpstreamFailure(UpstreamName("altinn-pdp"), UpstreamFailureStage.REQUEST, null, IllegalStateException()),
                UpstreamFailure(UpstreamName("altinn-pdp"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException()),
            ).forEach { failure ->
                listOf(REQUESTED_ORG, SYSTEM_USER_ORG).forEach { failingOrganization ->
                    test("${failure.upstream} ${failure.stage} ${failure.status} on $failingOrganization returns the original failure without logging a rejection") {
                        val fixture = AccessFixture()
                        fixture.pdp.failures[failingOrganization] = failure
                        fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)

                        fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                            .shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs failure
                        fixture.eregRequests shouldBe if (failingOrganization == REQUESTED_ORG) 0 else 1
                        fixture.pdp.requestedOrganizations shouldBe if (failingOrganization == REQUESTED_ORG) {
                            listOf(setOf(REQUESTED_ORG))
                        } else {
                            listOf(setOf(REQUESTED_ORG), setOf(SYSTEM_USER_ORG))
                        }
                        capture.records.shouldBeEmpty()
                    }
                }
            }

            decisions.filter { it != AuthorizationDecision.Permit }.forEach { directDecision ->
                test("Ereg failure after direct $directDecision stops before fallback without granting, denying or logging") {
                    val fixture = AccessFixture()
                    fixture.pdp.decisions[REQUESTED_ORG] = directDecision
                    fixture.pdp.permit(SYSTEM_USER_ORG)
                    fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)
                    val upstreamFailure = UpstreamFailure(
                        UpstreamName("ereg"),
                        UpstreamFailureStage.RESPONSE,
                        503,
                        IllegalStateException("private upstream body $REQUESTED_ORG"),
                    )
                    fixture.ereg.setFailure(upstreamFailure)

                    fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                        .shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs upstreamFailure
                    fixture.pdp.requestedOrganizations shouldContainExactly listOf(setOf(REQUESTED_ORG))
                    capture.records.shouldBeEmpty()
                }
            }

            test("missing organization preserves the bad request contract") {
                val fixture = AccessFixture()
                val failure = shouldThrow<ApiErrorException.BadRequestException> {
                    fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                }
                failure.message shouldBe "Unable to look up the organization"
                failure.type shouldBe ErrorType.ORGANIZATION_NOT_FOUND
            }

            test("propagates cancellation without logging a rejection") {
                val fixture = AccessFixture()
                fixture.pdp.cancellation = CancellationException("Request cancelled")
                fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(SYSTEM_USER_ORG)

                shouldThrow<CancellationException> {
                    fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                }

                capture.records.shouldBeEmpty()
            }

            test("keeps an existing trace id without synthesizing a new one") {
                val traceId = "0123456789abcdef0123456789abcdef"
                val fixture = AccessFixture()
                fixture.ereg.organisasjoner[REQUESTED_ORG] = organisationWithParent(UNRELATED_ORG)

                MDC.put("trace_id", traceId)
                try {
                    fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                } finally {
                    MDC.remove("trace_id")
                }

                rejectionRecord()["trace_id"].asText() shouldBe traceId
            }
        }
    })

private const val PERSON_IDENT = "12345678901"
private const val ACCESS_TOKEN = "privacy-canary-token"
private const val SYSTEM_USER_ID = "privacy-canary-system-user"
private const val REQUESTED_ORG = "910000001"
private const val SYSTEM_USER_ORG = "910000002"
private const val UNRELATED_ORG = "910000003"

private fun JsonNode.shouldBeRejection(directDecision: String, fallbackDecision: String) {
    this["level"].asText() shouldBe "WARN"
    this["message"].asText() shouldBe "System user access was not granted after resource and organization checks"
    this["logger_name"].asText() shouldBe AltinnOrganizationAccess::class.java.name
    this["event_type"].asText() shouldBe "api_request_rejected"
    this["operation"].asText() shouldBe "validate_system_user_access"
    this["error_code"].asText() shouldBe "MISSING_ALTINN_RESOURCE_ACCESS"
    this["rejection_reason"].asText() shouldBe "SYSTEM_USER_ACCESS_NOT_GRANTED"
    this["pdp_decision"].asText() shouldBe directDecision
    this["pdp_fallback_decision"].asText() shouldBe fallbackDecision
}

private class AccessFixture {
    val altinn = FakeAltinnTilgangerClient().also { it.accessPolicy.clear() }
    val pdp = RecordingAltinnAuthorizationClient()
    val ereg = FakeEregClient().also { it.organisasjoner.clear() }
    private val recordingEreg = RecordingEregClient { ereg.getOrganisasjon(it) }
    val eregRequests get() = recordingEreg.requests.size
    private val eregCache = mockk<EregCache> {
        every { getOrganisasjon(any()) } returns null
        every { putOrganisasjon(any(), any()) } just runs
    }
    val access = AltinnOrganizationAccess(
        AltinnTilgangerService(altinn),
        pdp,
        CachedEregClient(recordingEreg, eregCache),
    )
}

/** Answers [AuthorizationDecision.Deny] for every organization without an explicit decision or failure. */
private class RecordingAltinnAuthorizationClient : AltinnAuthorizationClient {
    val decisions = mutableMapOf<String, AuthorizationDecision>()
    val requestedOrganizations = mutableListOf<Set<String>>()
    val failures = mutableMapOf<String, UpstreamFailure>()
    var cancellation: CancellationException? = null

    fun permit(orgNumber: String) {
        decisions[orgNumber] = AuthorizationDecision.Permit
    }

    override suspend fun authorize(user: User, orgNumberSet: Set<String>, resource: String): UpstreamResult<AuthorizationDecision> {
        requestedOrganizations += orgNumberSet
        cancellation?.let { throw it }
        val organization = orgNumberSet.single()
        failures[organization]?.let { return UpstreamResult.Failure(it) }
        return UpstreamResult.Success(decisions[organization] ?: AuthorizationDecision.Deny)
    }
}

private fun personnelManager() = OrganizationAccessSubject.PersonnelManager(
    personIdent = PersonalIdentificationNumber(PERSON_IDENT),
    accessToken = AccessToken(ACCESS_TOKEN),
)

private fun systemUser() = OrganizationAccessSubject.LpsSystemUser(
    systemUserId = SYSTEM_USER_ID,
    systemUserOrganizationNumber = OrganizationNumber(SYSTEM_USER_ORG),
)

private fun organisationWithParent(parentOrgNumber: String) = Organisasjon(
    organisasjonsnummer = REQUESTED_ORG,
    inngaarIJuridiskEnheter = listOf(Organisasjon(organisasjonsnummer = parentOrgNumber)),
)
